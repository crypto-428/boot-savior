package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A contiguous run of bytes inside the image. */
data class IsoExtent(val offset: Long, val length: Long)

/** One file or directory inside the image (ISO9660/Joliet or UDF). */
data class IsoEntry(
    /** Path relative to the image root, using '/' and no leading slash. */
    val path: String,
    val isDirectory: Boolean,
    /** Absolute byte offset of the file's first extent inside the image. */
    val offset: Long,
    val size: Long,
    /**
     * Every run this file occupies. ISO9660 files are almost always a single
     * extent; UDF files (a >4 GB `install.wim`, for instance) are frequently
     * fragmented, so reads must be routed through [readAt].
     */
    val extents: List<IsoExtent> = listOf(IsoExtent(offset, size)),
    /** UDF only: the file's bytes stored inside its File Entry. */
    val inlineData: ByteArray? = null,
    /** ISO9660 only: this record has the multi-extent flag, more records follow. */
    val multiExtentOpen: Boolean = false

) {
    /** Reads [length] bytes at [offset] within this file, crossing extents as needed. */
    fun readAt(source: ByteReader, offset: Long, length: Int): ByteArray {
        inlineData?.let { data ->
            val out = ByteArray(length)
            val from = offset.toInt().coerceIn(0, data.size)
            val take = minOf(length, data.size - from)
            if (take > 0) System.arraycopy(data, from, out, 0, take)
            return out
        }
        if (extents.size == 1) return source.readAt(extents[0].offset + offset, length)

        val out = ByteArray(length)
        var remaining = length
        var cursor = offset
        var written = 0
        for (extent in extents) {
            if (remaining <= 0) break
            if (cursor >= extent.length) {
                cursor -= extent.length
                continue
            }
            val take = minOf(remaining.toLong(), extent.length - cursor).toInt()
            val chunk = source.readAt(extent.offset + cursor, take)
            System.arraycopy(chunk, 0, out, written, take)
            written += take
            remaining -= take
            cursor = 0
        }
        return out
    }
}


/**
 * Minimal ISO9660 reader: enough to enumerate the file tree and read file bytes
 * so the contents can be copied onto a FAT32 partition. Prefers the Joliet
 * supplementary descriptor when present (long, Unicode filenames), otherwise it
 * falls back to the plain ISO9660 primary descriptor.
 *
 * Rock Ridge extensions are ignored: for bootable install media the Joliet or
 * 8.3 names are what the firmware and bootloaders look for anyway.
 */
class Iso9660Reader(private val source: ByteReader) {

    companion object {
        const val SECTOR_SIZE = 2048
        private const val MAX_DEPTH = 12
    }

    /** Volume identifier from the primary descriptor, trimmed. */
    var volumeLabel: String = ""
        private set

    private var rootRecord: ByteArray? = null
    private var joliet = false

    /** True if a valid ISO9660 primary volume descriptor was found. */
    fun parse(): Boolean {
        var primaryRoot: ByteArray? = null
        var jolietRoot: ByteArray? = null

        for (sector in 16 until 32) {
            val vd = source.readAt(sector.toLong() * SECTOR_SIZE, SECTOR_SIZE)
            val id = String(vd, 1, 5, Charsets.US_ASCII)
            if (id != "CD001") break
            when (vd[0].toInt() and 0xFF) {
                1 -> {
                    volumeLabel = String(vd, 40, 32, Charsets.US_ASCII).trim()
                    primaryRoot = vd.copyOfRange(156, 156 + 34)
                }
                2 -> {
                    if (isJolietEscape(vd)) jolietRoot = vd.copyOfRange(156, 156 + 34)
                }
                255 -> break
            }
        }

        rootRecord = jolietRoot ?: primaryRoot
        joliet = jolietRoot != null
        return rootRecord != null
    }

    private fun isJolietEscape(vd: ByteArray): Boolean {
        // Escape sequences at offset 88: 25 2F 40 / 43 / 45 mark UCS-2 levels 1..3.
        val a = vd[88].toInt() and 0xFF
        val b = vd[89].toInt() and 0xFF
        val c = vd[90].toInt() and 0xFF
        return a == 0x25 && b == 0x2F && (c == 0x40 || c == 0x43 || c == 0x45)
    }

    /** Depth-first listing of every file and directory in the image. */
    fun listAll(): List<IsoEntry> {
        val root = rootRecord ?: return emptyList()
        val out = mutableListOf<IsoEntry>()
        val extent = readIntLe(root, 2).toLong() and 0xFFFFFFFFL
        val length = readIntLe(root, 10).toLong() and 0xFFFFFFFFL
        walk(extent, length, "", 0, out)
        return out
    }

    private fun walk(extentLba: Long, byteLength: Long, prefix: String, depth: Int, out: MutableList<IsoEntry>) {
        if (depth > MAX_DEPTH || byteLength <= 0) return
        val data = source.readAt(extentLba * SECTOR_SIZE, byteLength.toInt())

        var offset = 0
        while (offset < data.size) {
            val recordLength = data[offset].toInt() and 0xFF
            if (recordLength == 0) {
                // Records never straddle a sector boundary; skip to the next sector.
                val next = ((offset / SECTOR_SIZE) + 1) * SECTOR_SIZE
                if (next >= data.size) break
                offset = next
                continue
            }
            if (offset + recordLength > data.size) break

            val flags = data[offset + 25].toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            val nameLength = data[offset + 32].toInt() and 0xFF
            val name = decodeName(data, offset + 33, nameLength)
            val childLba = readIntLe(data, offset + 2).toLong() and 0xFFFFFFFFL
            val childSize = readIntLe(data, offset + 10).toLong() and 0xFFFFFFFFL

            // "." and ".." are single-byte records containing 0x00 / 0x01.
            val isSelfOrParent = nameLength == 1 &&
                ((data[offset + 33].toInt() and 0xFF) == 0 || (data[offset + 33].toInt() and 0xFF) == 1)

            if (!isSelfOrParent && name.isNotEmpty()) {
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val previous = out.lastOrNull()
                // Bit 7 of the flags marks a multi-extent file: the next record
                // continues the same file. Large images (>4 GB payloads written
                // by xorriso) rely on this, and treating each record as its own
                // file used to truncate them.
                if (previous != null && !previous.isDirectory && previous.path == path && previous.multiExtentOpen) {
                    out[out.size - 1] = previous.copy(
                        size = previous.size + childSize,
                        extents = previous.extents + IsoExtent(childLba * SECTOR_SIZE, childSize),
                        multiExtentOpen = (flags and 0x80) != 0
                    )
                } else {
                    out.add(
                        IsoEntry(
                            path = path,
                            isDirectory = isDir,
                            offset = childLba * SECTOR_SIZE,
                            size = childSize,
                            extents = listOf(IsoExtent(childLba * SECTOR_SIZE, childSize)),
                            multiExtentOpen = !isDir && (flags and 0x80) != 0
                        )
                    )
                }
                if (isDir) walk(childLba, childSize, path, depth + 1, out)
            }


            offset += recordLength
        }
    }

    private fun decodeName(data: ByteArray, start: Int, length: Int): String {
        if (length <= 0) return ""
        val raw = if (joliet) {
            String(data, start, length, Charsets.UTF_16BE)
        } else {
            String(data, start, length, Charsets.US_ASCII)
        }
        // ISO9660 appends a ";1" version suffix to file names.
        val semicolon = raw.indexOf(';')
        val trimmed = if (semicolon >= 0) raw.substring(0, semicolon) else raw
        return trimmed.trim().trimEnd('.')
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
