package com.yourapp.USBooter.util

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Repairs the on-disk layout *after* an ISO has been raw-cloned onto the stick.
 *
 * A raw clone copies the image's own partition table verbatim, which is why a
 * freshly written stick shows "OS partition + a small FAT32 partition + a huge
 * chunk of unallocated space": the table describes the ISO, not the drive.
 *
 *  1. If the image carries a GPT, its *backup* GPT sits at the end of the ISO,
 *     not at the end of the drive, and LastUsableLBA is wrong, so strict
 *     firmware treats the table as damaged.
 *  2. The leftover space is unusable.
 *
 * This pass moves the backup GPT to the end of the drive, fixes the protective
 * MBR, makes sure a legacy BIOS has an active partition to chainload, and
 * optionally turns the free tail into a normal data partition.
 *
 * ## The rule that must never be broken
 *
 * Everything below `imageSizeBytes` belongs to the cloned image. Hybrid ISOs
 * (Ubuntu, Debian, Fedora...) describe their own payload with an MBR entry of
 * type 0x00 ("Empty") or with a GPT entry that starts at LBA 0, so a naive
 * "end of the last real partition" scan lands a few MB into the drive and the
 * data partition then gets formatted straight over the ISO9660 filesystem.
 * The stick still starts GRUB (its boot code lives in the first sectors) but
 * GRUB then reports `file '/boot/...' not found` / `no such device: /.disk/info`.
 * That is exactly the failure this class must not cause, so the image size is
 * always the hard floor for anything we allocate.
 */
object PostCloneLayout {

    private const val TAG = "PostCloneLayout"
    private const val ALIGNMENT_SECTORS = 2048L            // 1 MiB
    private const val MIN_DATA_PARTITION_SECTORS = 131072L // 64 MiB - below this it isn't worth it
    private val BASIC_DATA_TYPE_GUID = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")

    /**
     * @param imageSizeBytes size of the ISO that was cloned - hard lower bound for the free tail.
     * @param dataFilesystem filesystem for the leftover space, or null to leave it unallocated.
     * @return human-readable summary of what was repaired/created.
     */
    fun repair(
        device: UsbBulkStorageDevice,
        imageSizeBytes: Long,
        dataFilesystem: Filesystem?,
        progress: (String) -> Unit = {}
    ): String {
        val notes = mutableListOf<String>()
        val blockSize = device.blockSize
        val totalBlocks = device.totalBlocks

        require(imageSizeBytes > 0) { "The selected image is empty" }
        val imageSectors = (imageSizeBytes + blockSize - 1) / blockSize
        require(imageSectors <= totalBlocks) { "The image is larger than the drive" }

        val mbr = device.readBlocks(0, 1)
        val hasGpt = isGptHeader(device.readBlocks(1, 1))

        var reservedTailSectors = 0L
        // The cloned image owns every sector it occupies, whether or not the
        // partition table admits it. Start from there, never below.
        var usedEnd = maxOf(imageSectors, mbrUsedEnd(mbr))
        var gptOk = false

        if (hasGpt) {
            progress("Relocating the backup GPT to the end of the drive")
            val gpt = relocateGpt(device)
            if (gpt != null) {
                gptOk = true
                reservedTailSectors = gpt.arraySectors + 1
                usedEnd = maxOf(usedEnd, gpt.highestUsedLba + 1)
                notes.add("backup GPT rebuilt at the end of the drive")
            } else {
                notes.add("GPT looked unusual, so it was left untouched")
            }
        }

        // Do not "fix" the active flag or install different boot code here.
        // Isohybrid images use deliberately unusual MBR entries and their own
        // stage-1 loader. Changing which entry is active can still start GRUB
        // but make it look for the wrong embedded root device. A raw clone must
        // retain the image author's boot selection byte-for-byte.

        if (dataFilesystem != null) {
            notes.add(createDataPartition(device, usedEnd, reservedTailSectors, hasGpt, gptOk, dataFilesystem, progress))
        }

        device.synchronizeCache()
        return notes.joinToString("; ").ifBlank { "layout left as written by the image" }
    }

    /**
     * Turns the free tail into a real partition. Order matters: the slot in the
     * partition table is claimed *before* a single filesystem byte is written,
     * so a drive whose table is full is never partly overwritten.
     */
    private fun createDataPartition(
        device: UsbBulkStorageDevice,
        usedEnd: Long,
        reservedTailSectors: Long,
        hasGpt: Boolean,
        gptOk: Boolean,
        fs: Filesystem,
        progress: (String) -> Unit
    ): String {
        val blockSize = device.blockSize
        // Keep a full alignment unit as a guard after the final image sector.
        // Besides making the new filesystem conventionally aligned, this makes
        // it impossible for formatter metadata to touch a partially filled last
        // image block or image-tail metadata that firmware may still inspect.
        val start = align(usedEnd + ALIGNMENT_SECTORS)
        val limit = device.totalBlocks - reservedTailSectors
        val sectors = limit - start

        if (sectors < MIN_DATA_PARTITION_SECTORS) return "not enough leftover space for a data partition"
        if (hasGpt && !gptOk) return "free space left unallocated (the image's GPT could not be extended safely)"

        // Claim the table slot first - if this fails nothing has been written yet.
        val claim =
            if (hasGpt) claimGptEntry(device, start, sectors, "DATA")
            else claimMbrEntry(device, start, sectors, fs)
        if (!claim) return "no free partition slot left for the extra data partition"

        progress("Formatting the free ${sectors * blockSize / 1_000_000} MB as ${fs.displayName}")
        formatDataPartition(device, start, sectors, fs)
        device.synchronizeCache()
        return "leftover ${sectors * blockSize / 1_000_000} MB formatted as ${fs.displayName}"
    }

    // ── MBR ──────────────────────────────────────────────────────────────

    /**
     * End (exclusive) of the furthest region any MBR entry claims.
     *
     * Type 0x00 entries are deliberately included: isohybrid images describe
     * their whole ISO payload with exactly such an entry, and skipping it is
     * what used to make the data partition land on top of the OS files.
     * The 0xEE protective entry is skipped because it spans the whole disk.
     */
    private fun mbrUsedEnd(mbr: ByteArray): Long {
        var end = ALIGNMENT_SECTORS
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val type = mbr[base + 4].toInt() and 0xFF
            if (type == 0xEE) continue
            val start = readUInt(mbr, base + 8)
            val size = readUInt(mbr, base + 12)
            if (size > 0) end = maxOf(end, start + size)
        }
        return end
    }

    private fun claimMbrEntry(
        device: UsbBulkStorageDevice,
        start: Long,
        sectors: Long,
        fs: Filesystem
    ): Boolean {
        val mbr = device.readBlocks(0, 1)
        val slot = (0 until 4).firstOrNull { i ->
            val base = 446 + i * 16
            (mbr[base + 4].toInt() and 0xFF) == 0 && readUInt(mbr, base + 12) == 0L
        } ?: return false

        // 32-bit LBA fields: an entry past 2 TiB simply cannot be described in MBR.
        if (start + sectors > 0xFFFFFFFFL) return false

        val base = 446 + slot * 16
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(base)
        buf.put(0x00.toByte()) // not bootable
        buf.put(Mbr.chs(start))
        buf.put(Mbr.partitionTypeId(MbrPartitionEntry(start, sectors, fs, false)))
        buf.put(Mbr.chs(start + sectors - 1))
        buf.putInt(start.toInt())
        buf.putInt(sectors.toInt())
        device.writeBlocks(0, mbr)
        return true
    }

    // ── GPT ──────────────────────────────────────────────────────────────

    private data class GptLayout(val arraySectors: Long, val highestUsedLba: Long)

    private fun isGptHeader(sector: ByteArray) =
        sector.size >= 8 && String(sector, 0, 8, Charsets.US_ASCII) == "EFI PART"

    /**
     * Moves the backup header + entry array to the very end of the drive and
     * updates LastUsableLBA / AlternateLBA / CRCs in both headers, plus the
     * protective MBR's size field.
     */
    private fun relocateGpt(device: UsbBulkStorageDevice): GptLayout? {
        val blockSize = device.blockSize
        val lastLba = device.totalBlocks - 1

        val header = device.readBlocks(1, 1)
        if (!isGptHeader(header)) return null
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val headerSize = hb.getInt(12)
        val entryLba = hb.getLong(72)
        val entryCount = hb.getInt(80)
        val entrySize = hb.getInt(84)
        if (headerSize !in 92..blockSize || entryCount <= 0 || entrySize < 128 ||
            entryLba < 2 || entryCount.toLong() * entrySize > 1L shl 20
        ) {
            Log.w(TAG, "Implausible GPT header, leaving it alone")
            return null
        }

        val arraySectors = ((entryCount.toLong() * entrySize) + blockSize - 1) / blockSize
        val array = device.readBlocks(entryLba, arraySectors.toInt())

        var highest = 0L
        val ab = ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until entryCount) {
            val off = i * entrySize
            if (off + entrySize > array.size) break
            if (ab.getLong(off) == 0L && ab.getLong(off + 8) == 0L) continue
            highest = maxOf(highest, ab.getLong(off + 40)) // ending LBA
        }

        val backupArrayLba = lastLba - arraySectors
        val lastUsable = backupArrayLba - 1
        if (lastUsable <= hb.getLong(40)) {
            Log.w(TAG, "Drive too small for the image's GPT, leaving it alone")
            return null
        }

        // Primary header: point at the new backup and the real end of the drive.
        hb.putLong(32, lastLba)     // AlternateLBA
        hb.putLong(48, lastUsable)  // LastUsableLBA
        writeHeaderCrc(header, headerSize)
        device.writeBlocks(1, header)

        writeBackupGpt(device, header, headerSize, array, backupArrayLba, lastLba)

        // Protective MBR must cover the whole drive, not just the image.
        val mbr = device.readBlocks(0, 1)
        for (i in 0 until 4) {
            val base = 446 + i * 16
            if ((mbr[base + 4].toInt() and 0xFF) == 0xEE) {
                val mb = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
                mb.putInt(base + 8, 1)
                mb.putInt(base + 12, if (lastLba > 0xFFFFFFFFL) 0xFFFFFFFF.toInt() else lastLba.toInt())
                device.writeBlocks(0, mbr)
                break
            }
        }

        return GptLayout(arraySectors, highest)
    }

    /**
     * Adds one basic-data entry and refreshes both GPT copies.
     *
     * Images written by xorriso often declare only as many entries as they use,
     * so when every declared slot is taken the entry count is grown - but only
     * into space the array already occupies on disk, never into the sectors the
     * ISO payload lives in.
     */
    private fun claimGptEntry(
        device: UsbBulkStorageDevice,
        start: Long,
        sectors: Long,
        name: String
    ): Boolean {
        val blockSize = device.blockSize
        val lastLba = device.totalBlocks - 1
        val header = device.readBlocks(1, 1)
        if (!isGptHeader(header)) return false
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = hb.getInt(12)
        val entryLba = hb.getLong(72)
        var entryCount = hb.getInt(80)
        val entrySize = hb.getInt(84)
        if (headerSize !in 92..blockSize || entryCount <= 0 || entrySize < 128) return false

        val arraySectors = ((entryCount.toLong() * entrySize) + blockSize - 1) / blockSize
        val array = device.readBlocks(entryLba, arraySectors.toInt())
        val ab = ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN)

        var slot = (0 until entryCount).firstOrNull { i ->
            val off = i * entrySize
            off + entrySize <= array.size && ab.getLong(off) == 0L && ab.getLong(off + 8) == 0L
        } ?: -1

        if (slot < 0) {
            // Grow the count into the padding the array already has on disk.
            val capacity = (array.size / entrySize)
            if (entryCount >= capacity) return false
            slot = entryCount
            entryCount += 1
            hb.putInt(80, entryCount)
        }

        // Refuse to describe a partition that would overlap anything already there.
        val end = start + sectors - 1
        for (i in 0 until entryCount) {
            val off = i * entrySize
            if (off + entrySize > array.size || i == slot) continue
            if (ab.getLong(off) == 0L && ab.getLong(off + 8) == 0L) continue
            val eStart = ab.getLong(off + 32)
            val eEnd = ab.getLong(off + 40)
            if (start <= eEnd && eStart <= end) {
                Log.w(TAG, "Refusing to add an overlapping data partition")
                return false
            }
        }
        if (end > hb.getLong(48)) return false // past LastUsableLBA

        val off = slot * entrySize
        ab.position(off)
        putGuid(ab, BASIC_DATA_TYPE_GUID)
        putGuid(ab, UUID.randomUUID())
        ab.putLong(start)
        ab.putLong(end)
        ab.putLong(0L)
        val nameBytes = name.take(36).toByteArray(Charsets.UTF_16LE)
        java.util.Arrays.fill(array, off + 56, off + 128, 0)
        System.arraycopy(nameBytes, 0, array, off + 56, nameBytes.size)

        hb.putInt(88, crc32(array, 0, entryCount * entrySize).toInt())
        writeHeaderCrc(header, headerSize)

        device.writeBlocks(entryLba, array)
        device.writeBlocks(1, header)
        writeBackupGpt(device, header, headerSize, array, lastLba - arraySectors, lastLba)
        return true
    }

    /** Backup header is the primary with MyLBA/AlternateLBA/PartitionEntryLBA swapped. */
    private fun writeBackupGpt(
        device: UsbBulkStorageDevice,
        primaryHeader: ByteArray,
        headerSize: Int,
        array: ByteArray,
        backupArrayLba: Long,
        lastLba: Long
    ) {
        val backup = primaryHeader.copyOf()
        val bb = ByteBuffer.wrap(backup).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(24, lastLba)         // MyLBA
        bb.putLong(32, 1L)              // AlternateLBA
        bb.putLong(72, backupArrayLba)  // PartitionEntryLBA
        writeHeaderCrc(backup, headerSize)
        device.writeBlocks(backupArrayLba, array)
        device.writeBlocks(lastLba, backup)
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private fun formatDataPartition(
        device: UsbBulkStorageDevice,
        start: Long,
        sectors: Long,
        fs: Filesystem
    ) {
        require(start > 0) { "refusing to format at LBA 0" }
        when (fs) {
            Filesystem.FAT32 -> Fat32Formatter.format(device, start, sectors, "DATA")
            Filesystem.EXFAT -> ExfatFormatter.format(device, start, sectors, "DATA")
            Filesystem.NTFS -> NtfsFormatter.format(device, start, sectors, "DATA")
            Filesystem.FAT16, Filesystem.FAT12 -> FatLegacyFormatter.format(device, start, sectors, "DATA", fs == Filesystem.FAT12)
            Filesystem.EXT4, Filesystem.EXT2, Filesystem.LINUX_SWAP -> LinuxFs.format(device, start, sectors, "DATA", fs)
        }
    }

    private fun writeHeaderCrc(header: ByteArray, headerSize: Int) {
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.putInt(16, 0)
        val crc = crc32(header, 0, headerSize).toInt()
        hb.putInt(16, crc)
    }

    private fun putGuid(buf: ByteBuffer, uuid: UUID) {
        val raw = ByteArray(16)
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
        val out = ByteArray(16)
        out[0] = raw[3]; out[1] = raw[2]; out[2] = raw[1]; out[3] = raw[0]
        out[4] = raw[5]; out[5] = raw[4]
        out[6] = raw[7]; out[7] = raw[6]
        System.arraycopy(raw, 8, out, 8, 8)
        buf.put(out)
    }

    private fun crc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    private fun readUInt(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun align(lba: Long): Long =
        ((lba + ALIGNMENT_SECTORS - 1) / ALIGNMENT_SECTORS) * ALIGNMENT_SECTORS
}
