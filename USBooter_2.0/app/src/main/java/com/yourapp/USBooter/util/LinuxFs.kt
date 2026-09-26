package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linux partition types: ext4, ext2 and swap. Formatting, detection and the
 * partition-table type codes they need under MBR and GPT.
 */
object LinuxFs {

    /** MBR type byte for Linux filesystems and swap. */
    const val MBR_LINUX = 0x83
    const val MBR_SWAP = 0x82

    /** GPT "Linux filesystem data" and "Linux swap" type GUIDs, as stored on disk. */
    val LINUX_DATA_GUID: ByteArray = guidBytes("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
    val LINUX_SWAP_GUID: ByteArray = guidBytes("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F")

    fun isLinux(fs: Filesystem) = fs == Filesystem.EXT4 || fs == Filesystem.EXT3 || fs == Filesystem.EXT2 || fs == Filesystem.LINUX_SWAP

    fun mbrType(fs: Filesystem): Int = if (fs == Filesystem.LINUX_SWAP) MBR_SWAP else MBR_LINUX

    fun gptType(fs: Filesystem): ByteArray = if (fs == Filesystem.LINUX_SWAP) LINUX_SWAP_GUID else LINUX_DATA_GUID

    fun format(device: BlockDevice, start: Long, sectors: Long, label: String, fs: Filesystem) {
        val clean = label.ifBlank { if (fs == Filesystem.LINUX_SWAP) "swap" else "LINUX" }
        when (fs) {
            Filesystem.EXT4 -> Ext2Formatter.format(device, start, sectors, clean, ext4 = true)
            Filesystem.EXT3 -> Ext2Formatter.format(device, start, sectors, clean, ext3 = true)
            Filesystem.EXT2 -> Ext2Formatter.format(device, start, sectors, clean)
            Filesystem.LINUX_SWAP -> formatSwap(device, start, sectors, clean)
            else -> throw IllegalArgumentException("${fs.displayName} is not a Linux filesystem")
        }
    }

    /** mkswap equivalent: one 4 KiB header page, version 1, no bad pages. */
    fun formatSwap(device: BlockDevice, start: Long, sectors: Long, label: String) {
        val pages = sectors * device.blockSize / 4096
        require(pages >= 10) { "This partition is too small for Linux swap (at least 40 KB is needed)" }
        require(4096 % device.blockSize == 0) { "Linux swap needs a sector size that divides 4096" }
        val page = ByteArray(4096)
        val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1024, 1)                                        // version
        b.putInt(1028, (pages - 1).coerceAtMost(0xFFFFFFFFL).toInt()) // last page
        b.putInt(1032, 0)                                        // bad pages
        val uuid = java.util.UUID.randomUUID()
        b.order(ByteOrder.BIG_ENDIAN)
        b.putLong(1036, uuid.mostSignificantBits); b.putLong(1044, uuid.leastSignificantBits)
        label.take(16).toByteArray(Charsets.US_ASCII).copyInto(page, 1052)
        "SWAPSPACE2".toByteArray(Charsets.US_ASCII).copyInto(page, 4086)
        device.writeBlocks(start, page)
        runCatching { device.synchronizeCache() }
    }

    /** Reads the start of a partition and returns "ext4"/"ext3"/"ext2"/"swap", or null. */
    fun detect(device: BlockDevice, start: Long): String? = runCatching {
        val count = (4096 / device.blockSize).coerceAtLeast(1)
        detectIn(device.readBlocks(start, count))
    }.getOrNull()

    fun detectIn(head: ByteArray): String? {
        if (head.size >= 4096 && String(head, 4086, 10, Charsets.US_ASCII) == "SWAPSPACE2") return "swap"
        if (head.size < 2048) return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(1024 + 56).toInt() and 0xFFFF) != 0xEF53) return null
        val compat = b.getInt(1024 + 92)
        val incompat = b.getInt(1024 + 96)
        val ro = b.getInt(1024 + 100)
        return when {
            incompat and 0x02C0 != 0 || ro and 0x0078 != 0 -> "ext4"
            compat and 0x0004 != 0 -> "ext3"
            else -> "ext2"
        }
    }

    /** Sectors the ext superblock says the volume spans (0 if unknown). */
    fun declaredSectors(device: BlockDevice, start: Long): Long = runCatching {
        val count = (4096 / device.blockSize).coerceAtLeast(1)
        val head = device.readBlocks(start, count)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(1024 + 56).toInt() and 0xFFFF) != 0xEF53) return 0L
        val blocks = b.getInt(1024 + 4).toLong() and 0xFFFFFFFFL
        val blockSize = 1024L shl b.getInt(1024 + 24)
        blocks * blockSize / device.blockSize
    }.getOrDefault(0L)

    private fun guidBytes(text: String): ByteArray {
        val u = java.util.UUID.fromString(text)
        val out = ByteArray(16)
        val msb = u.mostSignificantBits
        val lsb = u.leastSignificantBits
        // mixed-endian: first three fields little-endian, the rest big-endian
        val d1 = (msb ushr 32).toInt(); val d2 = ((msb ushr 16) and 0xFFFF).toInt(); val d3 = (msb and 0xFFFF).toInt()
        for (i in 0 until 4) out[i] = (d1 ushr (8 * i)).toByte()
        for (i in 0 until 2) out[4 + i] = (d2 ushr (8 * i)).toByte()
        for (i in 0 until 2) out[6 + i] = (d3 ushr (8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb ushr (8 * (7 - i))).toByte()
        return out
    }
}
