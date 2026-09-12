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
 *  - Only the MBR primary table is edited. A GPT drive is reported as read-only
 *    here rather than half-supported.
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

    /** Reads the four MBR primary entries in slot order, blanks included as null. */
    fun entries(device: BlockDevice): List<Entry?> {
        val s = device.readBlocks(0, 1)
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

    fun listDevice(device: BlockDevice): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        val gpt = (s[446 + 4].toInt() and 0xFF) == 0xEE
        val list = entries(device).filterNotNull().sortedBy { it.start }
        val array = JSONArray()
        list.forEachIndexed { position, e ->
            val boot = runCatching { device.readBlocks(e.start, 1) }.getOrNull()
            val fs = boot?.let { filesystemOf(it) }
            val fsSectors = boot?.let { declaredSectors(it, fs, device.blockSize) } ?: 0L
            val nextStart = list.getOrNull(position + 1)?.start ?: device.totalBlocks
            val minSectors = if (fs != null && fsSectors > 0) minOf(fsSectors, e.sectors) else e.sectors
            val maxSectors = nextStart - e.start
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
                    put("canDelete", !gpt)
                    put("canResize", !gpt && maxSectors > 0)
                    put("resizesFilesystem", fs == "NTFS")
                    put(
                        "note",
                        when {
                            gpt -> "This drive uses a GPT table, which this editor does not change"
                            fs == null -> "The filesystem here is not recognised, so its size cannot be changed safely"
                            fs == "NTFS" -> "NTFS is resized together with the partition"
                            else -> "The partition can be resized; the $fs filesystem keeps its current size"
                        }
                    )
                }
            )
        }
        val lastEnd = list.maxOfOrNull { it.start + it.sectors } ?: alignment(device)
        return JSONObject().apply {
            put("ok", true)
            put("table", if (gpt) "GPT" else "MBR")
            put("editable", !gpt)
            put("blockSize", device.blockSize)
            put("totalSectors", device.totalBlocks)
            put("freeStartLba", maxOf(lastEnd, alignment(device)))
            put("freeSectors", maxOf(0L, device.totalBlocks - maxOf(lastEnd, alignment(device))))
            put("partitions", array)
            put("summary", "${array.length()} partition(s) on this drive")
        }
    }

    fun deleteDevice(device: BlockDevice, index: Int): JSONObject {
        val s = runCatching { device.readBlocks(0, 1) }.getOrNull()
            ?: return failure("The first sector of the drive could not be read")
        if ((s[446 + 4].toInt() and 0xFF) == 0xEE) return failure("This drive uses a GPT table, which this editor does not change")
        if (index !in 1..4) return failure("There is no partition $index on this drive")
        val entries = entries(device)
        entries[index - 1] ?: return failure("Slot $index of the partition table is already empty")

        ByteArray(16).copyInto(s, 446 + (index - 1) * 16)
        // Keep exactly one bootable entry so firmware still finds something to start.
        if ((0 until 4).none { (s[446 + it * 16].toInt() and 0xFF) == 0x80 }) {
            val first = (0 until 4).firstOrNull { (s[446 + it * 16 + 4].toInt() and 0xFF) != 0 }
            if (first != null) s[446 + first * 16] = 0x80.toByte()
        }
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        device.writeBlocks(0, s)
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
        if ((s[446 + 4].toInt() and 0xFF) == 0xEE) return failure("This drive uses a GPT table, which this editor does not change")
        if (index !in 1..4) return failure("There is no partition $index on this drive")
        val all = entries(device).filterNotNull().sortedBy { it.start }
        val target = all.firstOrNull { it.index == index }
            ?: return failure("Slot $index of the partition table is empty")
        if (newSectors <= 0) return failure("The new size must be larger than zero")

        val next = all.firstOrNull { it.start > target.start }?.start ?: device.totalBlocks
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
        if (newSectors < fsSectors && !allowDataLoss) {
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
        // The table entry first: the filesystem is only touched after the geometry is real.
        put32(s, 446 + (index - 1) * 16 + 12, newSectors)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        device.writeBlocks(0, s)

        if (fs == "NTFS" && boot != null) {
            val fixed = boot.copyOf()
            put64(fixed, 40, newSectors - 1)
            // Write the end-of-partition copy first: while it is being written the
            // healthy main sector is still the recovery source.
            device.writeBlocks(target.start + newSectors - 1, fixed)
            device.writeBlocks(target.start, fixed)
            if (target.sectors != newSectors) {
                // The old copy now sits inside or outside the volume: clear it so a
                // stale boot sector cannot be mistaken for a second volume later.
                val oldCopy = target.start + target.sectors - 1
                if (oldCopy != target.start + newSectors - 1 && oldCopy < device.totalBlocks) {
                    runCatching { device.writeBlocks(oldCopy, ByteArray(device.blockSize)) }
                }
            }
            notes += "The NTFS volume and its boot-sector copy were resized with the partition"
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
        if ((s[446 + 4].toInt() and 0xFF) == 0xEE) return failure("This drive uses a GPT table, which this editor does not change")
        val slot = (0 until 4).firstOrNull { (s[446 + it * 16 + 4].toInt() and 0xFF) == 0 }
            ?: return failure("The partition table already holds four partitions. Delete one first.")
        if (startLba < alignment(device)) return failure("A partition cannot start before sector ${alignment(device)}")
        if (sectors <= 0 || startLba + sectors > device.totalBlocks) {
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
        // FAT32/exFAT formatters need the real USB transport; NTFS only needs writes.
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
        runCatching { device.synchronizeCache() }
        return listDevice(device).put(
            "summary",
            "A new ${fs.displayName} partition of $sectors sectors was created at sector $startLba"
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun alignment(device: BlockDevice): Long =
        (1024 * 1024 / device.blockSize).coerceAtLeast(1).toLong()

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
