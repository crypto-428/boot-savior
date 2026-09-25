package com.yourapp.USBooter.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Partition-Wizard style editing of an existing drive: list, delete, shrink,
 * extend and create partitions without reformatting the whole stick.
 *
 * The rules that keep this honest:
 *
 *  - MBR primary tables and GPT tables are both edited; GPT changes are written
 *    to the primary and backup copies with fresh CRCs.
 *  - A partition is never shrunk below the size its own filesystem declares, and
 *    never grown over its neighbour or past the last sector of the drive, unless
 *    the caller explicitly accepts data loss.
 *  - Shrinking or extending NTFS also rewrites its boot sector and the copy at
 *    the end of the partition, so the volume and the table agree afterwards.
 *    FAT32/exFAT keep their own size: the partition may end up larger than the
 *    filesystem, which mounts correctly, and the report says so.
 *  - No file content is ever moved. Anything that would need file relocation
 *    (moving a partition, or shrinking below the filesystem size) is refused with
 *    the reason, instead of being attempted and corrupting the volume.
 */
object PartitionManager {

    // -------------------------------------------------------------- public API

    /** Everything the editor UI needs: current entries and their safe size limits. */
    fun list(context: Context, deviceName: String): JSONObject =
        PartitionRepair.withDrive(context, deviceName) { listDevice(it) }

    fun delete(context: Context, deviceName: String, index: Int): JSONObject =
        PartitionRepair.withDrive(context, deviceName) { deleteDevice(it, index) }

    fun resize(
        context: Context,
        deviceName: String,
        index: Int,
        newSectors: Long,
        allowDataLoss: Boolean
    ): JSONObject = PartitionRepair.withDrive(context, deviceName) {
        resizeDevice(it, index, newSectors, allowDataLoss)
    }

    fun create(
        context: Context,
        deviceName: String,
        startLba: Long,
        sectors: Long,
        filesystem: String,
        label: String
    ): JSONObject = PartitionRepair.withDrive(context, deviceName) {
        createDevice(it, startLba, sectors, filesystem, label)
    }

    /** Moving a partition means copying every file: refused on purpose. */
    fun move(): JSONObject = failure(
        "Moving a partition means copying every file to a new place on the drive. " +
            "This app does not do that, because a power cut halfway through would lose the files. " +
            "Create a partition in the free space and copy your files there instead."
    )

    // ------------------------------------------------------------------ engine

    data class Entry(
        val index: Int,
        val start: Long,
        val sectors: Long,
        val typeByte: Int,
        val active: Boolean
    )

    /** A parsed GPT: primary header, its entry array, and the usable LBA window. */
    class GptTable(
        val header: ByteArray,
        val entriesLba: Long,
        val count: Int,
        val entrySize: Int,
        val entries: ByteArray,
        val backupLba: Long,
        val firstUsable: Long,
        val lastUsable: Long
    )

    private val BASIC_DATA_GUID = byteArrayOf(
        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(), 0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
        0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(), 0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte()
    )
    private val ESP_GUID = byteArrayOf(
        0x28, 0x73, 0x2A, 0xC1.toByte(), 0x1F, 0xF8.toByte(), 0xD2.toByte(), 0x11,
        0xBA.toByte(), 0x4B, 0x00, 0xA0.toByte(), 0xC9.toByte(), 0x3E, 0xC9.toByte(), 0x3B
    )

    fun readGpt(device: BlockDevice): GptTable? = runCatching {
        val h = device.readBlocks(1, 1)
        if (String(h, 0, 8, Charsets.US_ASCII) != "EFI PART") return null
        val entriesLba = le64(h, 72)
        val count = le32(h, 80).toInt()
        val size = le32(h, 84).toInt()
        if (count !in 1..1024 || size !in 128..1024 || entriesLba < 2) return null
        val bytes = count * size
        val blocks = (bytes + device.blockSize - 1) / device.blockSize
        val e = device.readBlocks(entriesLba, blocks)
        GptTable(h, entriesLba, count, size, e, le64(h, 32), le64(h, 40), le64(h, 48))
    }.getOrNull()

    private fun gptEntries(g: GptTable): List<Entry?> = (0 until g.count).map { i ->
        val o = i * g.entrySize
        val empty = (0 until 16).all { g.entries[o + it].toInt() == 0 }
        val first = le64(g.entries, o + 32)
        val last = le64(g.entries, o + 40)
        if (empty || first <= 0 || last < first) null
        else {
            val esp = (0 until 16).all { g.entries[o + it] == ESP_GUID[it] }
            Entry(i + 1, first, last - first + 1, if (esp) 0xEF else 0x07, false)
        }
    }

    private fun crc(b: ByteArray, off: Int, len: Int): Long =
        java.util.zip.CRC32().apply { update(b, off, len) }.value

    /** Writes the (modified) entry array to both GPT copies and re-stamps both header CRCs. */
    fun writeGpt(device: BlockDevice, g: GptTable) {
        val bytes = g.count * g.entrySize
        val entriesCrc = crc(g.entries, 0, bytes)
        fun stamp(h: ByteArray) {
            put32(h, 88, entriesCrc)
            val hs = le32(h, 12).toInt().coerceIn(92, h.size)
            put32(h, 16, 0)
            put32(h, 16, crc(h, 0, hs))
        }
        val backup = runCatching { device.readBlocks(g.backupLba, 1) }.getOrNull()
        if (backup != null && String(backup, 0, 8, Charsets.US_ASCII) == "EFI PART") {
            device.writeBlocks(le64(backup, 72), g.entries)
            stamp(backup)
            device.writeBlocks(g.backupLba, backup)
        }
        device.writeBlocks(g.entriesLba, g.entries)
        stamp(g.header)
        device.writeBlocks(1, g.header)
    }

    /** Reads the four MBR primary entries in slot order, blanks included as null. */
    fun entries(device: BlockDevice): List<Entry?> {
        val s = device.readBlocks(0, 1)
        if (isProtective(s)) readGpt(device)?.let { return gptEntries(it) }
        if (!hasSignature(s)) return List(4) { null }
        return (0 until 4).map { i ->
            val o = 446 + i * 16
            val type = s[o + 4].toInt() and 0xFF
            val start = le32(s, o + 8)
            val sectors = le32(s, o + 12)
            if (type == 0 || start <= 0 || sectors <= 0) null
            else Entry(i + 1, start, sectors, type, (s[o].toInt() and 0xFF) == 0x80)
        }
    }

    private fun isProtective(s: ByteArray) =
        (0 until 4).any { (s[446 + it * 16 + 4].toInt() and 0xFF) == 0xEE }

    /** Last sector (exclusive) partitions may use. */
    private fun endLimit(device: BlockDevice, g: GptTable?) =
        if (g != null) minOf(g.lastUsable + 1, device.totalBlocks) else device.totalBlocks

    fun listDevice(device: BlockDevice): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        val g = if (isProtective(s)) readGpt(device) else null
        if (isProtective(s) && g == null) {
            return failure("This drive says it uses GPT, but its GPT header could not be read. Use Repair first.")
        }
        val limit = endLimit(device, g)
        val minStart = maxOf(alignment(device), g?.firstUsable ?: 0L)
        val list = entries(device).filterNotNull().sortedBy { it.start }
        val array = JSONArray()
        list.forEachIndexed { position, e ->
            val boot = runCatching { device.readBlocks(e.start, 1) }.getOrNull()
            val fs = boot?.let { filesystemOf(it) }
            val fsSectors = boot?.let { declaredSectors(it, fs, device.blockSize) } ?: 0L
            val nextStart = list.getOrNull(position + 1)?.start ?: limit
            val ntfsMin = if (fs == "NTFS" && boot != null) NtfsResize.minimumSectors(device, e.start, boot)
                else if ((fs == "FAT32" || fs == "exFAT") && boot != null) FatShrink.minimumSectors(device, e.start, boot, fs)
                else null
            val minSectors = when {
                ntfsMin != null -> minOf(ntfsMin, e.sectors)
                fs != null && fsSectors > 0 -> minOf(fsSectors, e.sectors)
                else -> e.sectors
            }
            val maxSectors = maxOf(e.sectors, nextStart - e.start)
            array.put(
                JSONObject().apply {
                    put("index", e.index)
                    put("startLba", e.start)
                    put("sizeSectors", e.sectors)
                    put("filesystem", fs ?: "")
                    put("label", fs ?: "UNKNOWN")
                    put("typeByte", e.typeByte)
                    put("active", e.active)
                    put("fsSectors", fsSectors)
                    put("minSizeSectors", minSectors)
                    put("maxSizeSectors", maxSectors)
                    put("canDelete", true)
                    put("canResize", maxSectors > 0)
                    put("resizesFilesystem", fs == "NTFS" || fs == "FAT32" || fs == "exFAT")
                    put(
                        "note",
                        when {
                            fs == null -> "The filesystem here is not recognised, so its size cannot be changed safely"
                            fs == "NTFS" -> "NTFS is resized together with the partition"
                            fs == "FAT32" || fs == "exFAT" -> "When shrinking, files near the end are moved first so none are lost"
                            else -> "The partition can be resized; the $fs filesystem keeps its current size"
                        }
                    )
                }
            )
        }
        val lastEnd = list.maxOfOrNull { it.start + it.sectors } ?: minStart
        val freeStart = alignUp(maxOf(lastEnd, minStart), alignment(device))
        return JSONObject().apply {
            put("ok", true)
            put("table", if (g != null) "GPT" else "MBR")
            put("editable", true)
            put("blockSize", device.blockSize)
            put("totalSectors", device.totalBlocks)
            put("usableEnd", limit)
            put("freeStartLba", freeStart)
            put("freeSectors", maxOf(0L, limit - freeStart))
            put("partitions", array)
            put("summary", "${array.length()} partition(s) on this drive" + if (g != null) " (GPT)" else " (MBR)")
        }
    }

    fun deleteDevice(device: BlockDevice, index: Int): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        val g = if (isProtective(s)) readGpt(device) ?: return failure("The GPT header could not be read") else null
        val maxIndex = g?.count ?: 4
        if (index !in 1..maxIndex) return failure("There is no partition $index on this drive")
        entries(device)[index - 1] ?: return failure("Slot $index of the partition table is already empty")

        if (g != null) {
            ByteArray(g.entrySize).copyInto(g.entries, (index - 1) * g.entrySize)
            writeGpt(device, g)
        } else {
            ByteArray(16).copyInto(s, 446 + (index - 1) * 16)
            // Keep exactly one bootable entry so firmware still finds something to start.
            if ((0 until 4).none { (s[446 + it * 16].toInt() and 0xFF) == 0x80 }) {
                val first = (0 until 4).firstOrNull { (s[446 + it * 16 + 4].toInt() and 0xFF) != 0 }
                if (first != null) s[446 + first * 16] = 0x80.toByte()
            }
            s[510] = 0x55
            s[511] = 0xAA.toByte()
            device.writeBlocks(0, s)
        }
        runCatching { device.synchronizeCache() }
        return listDevice(device).put(
            "summary",
            "Partition $index was removed from the table. Its files are no longer reachable, " +
                "but the sectors themselves were not erased."
        )
    }

    fun resizeDevice(
        device: BlockDevice,
        index: Int,
        newSectors: Long,
        allowDataLoss: Boolean
    ): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        val g = if (isProtective(s)) readGpt(device) ?: return failure("The GPT header could not be read") else null
        val all = entries(device).filterNotNull().sortedBy { it.start }
        val target = all.firstOrNull { it.index == index }
            ?: return failure("There is no partition $index on this drive")
        if (newSectors <= 0) return failure("The new size must be larger than zero")

        val next = all.firstOrNull { it.start > target.start }?.start ?: endLimit(device, g)
        val maxSectors = next - target.start
        if (newSectors > maxSectors) {
            return failure(
                "Partition $index cannot grow to $newSectors sectors: only $maxSectors are free before the next " +
                    "partition or the end of the drive. Delete the partition after it first."
            )
        }

        val boot = runCatching { device.readBlocks(target.start, 1) }.getOrNull()
        val fs = boot?.let { filesystemOf(it) }
        val fsSectors = if (boot != null) declaredSectors(boot, fs, device.blockSize) else 0L
        val ntfsMin = if (fs == "NTFS" && boot != null) NtfsResize.minimumSectors(device, target.start, boot) else null
        if (fs == "NTFS" && ntfsMin != null && newSectors < ntfsMin) {
            return failure(
                "Partition $index has files up to $ntfsMin sectors, so it cannot be shrunk to $newSectors sectors " +
                    "without cutting them off. Choose a larger size."
            )
        }
        val fatMin = if ((fs == "FAT32" || fs == "exFAT") && boot != null) FatShrink.minimumSectors(device, target.start, boot, fs) else null
        if (fatMin != null && newSectors < fsSectors && newSectors < fatMin && !allowDataLoss) {
            return failure(
                "Partition $index has files that need at least $fatMin sectors, so it cannot be shrunk to $newSectors sectors. " +
                    "Choose a larger size."
            )
        }
        var fatDone = false
        if (fatMin != null && boot != null && newSectors < fsSectors && newSectors >= fatMin) {
            FatShrink.resize(device, target.start, newSectors, boot, fs!!)?.let { return failure(it) }
            fatDone = true
        }
        if (fs != "NTFS" && !fatDone && newSectors < fsSectors && !allowDataLoss) {
            return failure(
                "Partition $index holds a ${fs ?: "unknown"} filesystem that needs $fsSectors sectors. Shrinking it to " +
                    "$newSectors sectors would cut off files at the end of the volume, so it was not done. " +
                    "Free space inside the volume first, or allow data loss explicitly."
            )
        }
        if (fs == null && newSectors < target.sectors && !allowDataLoss) {
            return failure(
                "The filesystem in partition $index is not recognised, so there is no way to tell which sectors are in " +
                    "use. Shrinking it could lose files and was not done."
            )
        }

        val notes = mutableListOf<String>()
        // Shrinking NTFS: fit the filesystem first, so a failure leaves the partition untouched.
        var ntfsDone = false
        if (fs == "NTFS" && boot != null && newSectors < target.sectors) {
            NtfsResize.resize(device, target.start, target.sectors, newSectors, boot)?.let { return failure(it) }
            ntfsDone = true
        }
        // The table entry first: the filesystem is only touched after the geometry is real.
        if (g != null) {
            put64(g.entries, (index - 1) * g.entrySize + 40, target.start + newSectors - 1)
            writeGpt(device, g)
        } else {
            put32(s, 446 + (index - 1) * 16 + 12, newSectors)
            s[510] = 0x55
            s[511] = 0xAA.toByte()
            device.writeBlocks(0, s)
        }

        if (fs == "NTFS" && boot != null) {
            if (!ntfsDone) {
                NtfsResize.resize(device, target.start, target.sectors, newSectors, boot)?.let {
                    notes += it
                }
            }
            notes += "The NTFS volume and its boot-sector copy were resized with the partition"
        } else if (fatDone) {
            notes += "Files past the new end were moved and the $fs filesystem was shrunk with the partition"
        } else if (fs != null) {
            notes += "The partition entry was resized; the $fs filesystem keeps its own size of $fsSectors sectors"
        } else {
            notes += "The partition entry was resized; no filesystem was recognised inside it"
        }
        runCatching { device.synchronizeCache() }

        val verb = if (newSectors > target.sectors) "extended" else "shrunk"
        return listDevice(device).put(
            "summary",
            "Partition $index was $verb from ${target.sectors} to $newSectors sectors. " + notes.joinToString(". ")
        )
    }

    fun createDevice(
        device: BlockDevice,
        startLba: Long,
        sectors: Long,
        filesystem: String,
        label: String
    ): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        val g = if (isProtective(s)) readGpt(device) ?: return failure("The GPT header could not be read") else null
        val slot = if (g != null) {
            (0 until g.count).firstOrNull { i -> (0 until 16).all { g.entries[i * g.entrySize + it].toInt() == 0 } }
                ?: return failure("The partition table has no free slot left. Delete a partition first.")
        } else {
            (0 until 4).firstOrNull { (s[446 + it * 16 + 4].toInt() and 0xFF) == 0 }
                ?: return failure("The partition table already holds four partitions. Delete one first.")
        }
        val minStart = maxOf(alignment(device), g?.firstUsable ?: 0L)
        if (startLba < minStart) return failure("A partition cannot start before sector $minStart")
        if (sectors <= 0 || startLba + sectors > endLimit(device, g)) {
            return failure("A partition of $sectors sectors does not fit at sector $startLba on this drive")
        }
        val clash = entries(device).filterNotNull().firstOrNull { e ->
            startLba < e.start + e.sectors && e.start < startLba + sectors
        }
        if (clash != null) {
            return failure("That space is already used by partition ${clash.index}. Shrink or delete it first.")
        }

        val fs = when (filesystem.uppercase()) {
            "NTFS" -> Filesystem.NTFS
            "EXFAT" -> Filesystem.EXFAT
            else -> Filesystem.FAT32
        }
        val usb = device as? UsbBulkStorageDevice
        when (fs) {
            Filesystem.NTFS -> NtfsFormatter.format(device, startLba, sectors, label)
            Filesystem.FAT32 -> {
                usb ?: return failure("This drive cannot be formatted as FAT32 right now")
                Fat32Formatter.format(usb, startLba, sectors, label)
            }
            Filesystem.EXFAT -> {
                usb ?: return failure("This drive cannot be formatted as exFAT right now")
                ExfatFormatter.format(usb, startLba, sectors, label)
            }
        }
        if (g != null) {
            val o = slot * g.entrySize
            ByteArray(g.entrySize).copyInto(g.entries, o)
            BASIC_DATA_GUID.copyInto(g.entries, o)
            val u = java.util.UUID.randomUUID()
            put64(g.entries, o + 16, u.mostSignificantBits)
            put64(g.entries, o + 24, u.leastSignificantBits)
            put64(g.entries, o + 32, startLba)
            put64(g.entries, o + 40, startLba + sectors - 1)
            label.take(36).forEachIndexed { i, c ->
                g.entries[o + 56 + i * 2] = (c.code and 0xFF).toByte()
                g.entries[o + 57 + i * 2] = ((c.code shr 8) and 0xFF).toByte()
            }
            writeGpt(device, g)
        } else {
            val o = 446 + slot * 16
            ByteArray(16).copyInto(s, o)
            s[o + 4] = when (fs) {
                Filesystem.FAT32 -> 0x0C
                else -> 0x07
            }
            put32(s, o + 8, startLba)
            put32(s, o + 12, minOf(sectors, 0xFFFFFFFFL))
            if ((0 until 4).none { (s[446 + it * 16].toInt() and 0xFF) == 0x80 }) s[o] = 0x80.toByte()
            s[510] = 0x55
            s[511] = 0xAA.toByte()
            device.writeBlocks(0, s)
        }
        runCatching { device.synchronizeCache() }
        return listDevice(device).put(
            "summary",
            "A new ${fs.displayName} partition of $sectors sectors was created at sector $startLba"
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun alignment(device: BlockDevice): Long =
        (1024 * 1024 / device.blockSize).coerceAtLeast(1).toLong()

    private fun alignUp(v: Long, a: Long): Long = if (a <= 1) v else ((v + a - 1) / a) * a

    private fun hasSignature(s: ByteArray): Boolean =
        s.size >= 512 && (s[510].toInt() and 0xFF) == 0x55 && (s[511].toInt() and 0xFF) == 0xAA

    /** FAT32/exFAT/NTFS only: what the boot sector says its own volume spans. */
    private fun declaredSectors(boot: ByteArray, fs: String?, blockSize: Int): Long {
        if (fs == null || boot.size < 512) return 0
        val declared = when (fs) {
            "NTFS" -> le64(boot, 40) + 1
            "exFAT" -> le64(boot, 72)
            else -> {
                val small = le16(boot, 19).toLong()
                if (small > 0) small else le32(boot, 32)
            }
        }
        val bytesPerSector = le16(boot, 11)
        val scale = if (bytesPerSector in 512..4096 && bytesPerSector % blockSize == 0) {
            (bytesPerSector / blockSize).toLong()
        } else 1L
        val sectors = declared * scale
        return if (sectors <= 0 || sectors > (1L shl 44)) 0 else sectors
    }

    private fun filesystemOf(boot: ByteArray): String? {
        if (boot.size < 512) return null
        val oem = String(boot, 3, 8, Charsets.US_ASCII)
        if (oem == "EXFAT   ") return "exFAT"
        if (oem == "NTFS    ") return "NTFS"
        if (!hasSignature(boot)) return null
        if (String(boot, 82, 8, Charsets.US_ASCII).trim().startsWith("FAT32")) return "FAT32"
        if (String(boot, 54, 8, Charsets.US_ASCII).trim().startsWith("FAT")) return "FAT16"
        return null
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun le64(b: ByteArray, o: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun put64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun failure(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("summary", message)
        put("partitions", JSONArray())
    }
}
