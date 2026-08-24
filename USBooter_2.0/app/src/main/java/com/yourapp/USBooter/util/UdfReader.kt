package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal UDF (ECMA-167 / OSTA UDF 1.02-2.60) directory reader.
 *
 * Why this exists: Microsoft's Windows 10/11 installation images are ISO9660 +
 * Joliet + UDF hybrids, and the only volume format that can describe
 * `sources/install.wim` when it is 4 GB or larger is UDF. The ISO9660/Joliet
 * tree either omits that file or reports a truncated 32-bit size, which is why
 * the app used to either refuse a Windows ISO ("file too big for FAT32") or
 * copy a broken, short install.wim.
 *
 * Only the read path that matters here is implemented: anchor -> logical volume
 * descriptor -> partition descriptor -> file set descriptor -> root ICB, then a
 * recursive walk over File Identifier Descriptors. Both (Extended) File Entries
 * and short/long allocation descriptors are handled, including multi-extent
 * files, which is exactly what a >4 GB install.wim uses.
 */
class UdfReader(private val source: ByteReader) {

    companion object {
        private const val SECTOR = 2048
        private const val MAX_DEPTH = 16

        private const val TAG_PRIMARY_VOLUME = 1
        private const val TAG_PARTITION = 5
        private const val TAG_LOGICAL_VOLUME = 6
        private const val TAG_TERMINATING = 8
        private const val TAG_FILE_SET = 256
        private const val TAG_FILE_ID = 257

        private const val TAG_FILE_ENTRY = 261
        private const val TAG_EXTENDED_FILE_ENTRY = 266
    }

    var volumeLabel: String = ""
        private set

    private var blockSize = SECTOR
    private var partitionStartBlock = 0L
    private var fsdBlock = -1L
    private var rootIcbBlock = -1L
    private var rootIcbLength = 0

    /** Returns true when a usable UDF volume structure was found. */
    fun parse(): Boolean = runCatching { doParse() }.getOrDefault(false)

    private fun doParse(): Boolean {
        val anchor = findAnchor() ?: return false
        val vdsLength = le32(anchor, 16)
        val vdsLocation = le32u(anchor, 20)
        if (vdsLength <= 0) return false

        var lvdContentsUse: ByteArray? = null
        val sectors = (vdsLength / SECTOR).coerceAtMost(64)
        for (i in 0 until sectors) {
            val block = source.readAt((vdsLocation + i) * SECTOR, SECTOR)
            when (tagId(block)) {
                TAG_PRIMARY_VOLUME -> if (volumeLabel.isBlank()) {
                    volumeLabel = dstring(block, 24, 32)
                }
                TAG_PARTITION -> {
                    partitionStartBlock = le32u(block, 188)
                }
                TAG_LOGICAL_VOLUME -> {
                    val lbs = le32(block, 212)
                    if (lbs in 512..65536) blockSize = lbs
                    lvdContentsUse = block.copyOfRange(248, 264)
                    val id = dstring(block, 84, 128)
                    if (id.isNotBlank()) volumeLabel = id
                }
                TAG_TERMINATING -> {}
            }
        }

        val fsdAd = lvdContentsUse ?: return false
        // long_ad: 4-byte extent length, then the 4-byte logical block number.
        // Offset 8 is the partition reference number (always 0 here), which is why
        // the File Set Descriptor was never found on UDF-only Windows images.
        fsdBlock = le32u(fsdAd, 4)

        val fsd = readLogicalBlocks(fsdBlock, 1)
        if (tagId(fsd) != TAG_FILE_SET) return false
        // File Set Descriptor: root directory ICB (long_ad) at offset 400, so the
        // block number sits at 404 (400 is the extent length).
        rootIcbLength = le32(fsd, 400)
        rootIcbBlock = le32u(fsd, 404)
        return rootIcbBlock >= 0 && rootIcbLength > 0
    }


    /** Depth-first listing of every file and directory in the UDF volume. */
    fun listAll(): List<IsoEntry> {
        if (rootIcbBlock < 0) return emptyList()
        val out = mutableListOf<IsoEntry>()
        val seen = HashSet<Long>()
        walk(rootIcbBlock, rootIcbLength, "", 0, out, seen)
        return out
    }

    // ── Walking ──────────────────────────────────────────────────────────

    private fun walk(
        icbBlock: Long,
        icbLength: Int,
        prefix: String,
        depth: Int,
        out: MutableList<IsoEntry>,
        seen: MutableSet<Long>
    ) {
        if (depth > MAX_DEPTH) return
        if (!seen.add(icbBlock)) return
        val entry = readFileEntry(icbBlock, icbLength) ?: return
        if (!entry.isDirectory) return

        val data = readExtents(entry.extents, entry.size, entry.inlineData)
        var offset = 0
        while (offset + 38 <= data.size) {
            if (tagId(data, offset) != TAG_FILE_ID) break
            val impUseLength = le16(data, offset + 36)
            val nameLength = data[offset + 19].toInt() and 0xFF
            val characteristics = data[offset + 18].toInt() and 0xFF
            // FID ICB long_ad: length at +20, logical block number at +24.
            val childBlock = le32u(data, offset + 24)
            val childLength = le32(data, offset + 20)

            val nameStart = offset + 38 + impUseLength
            if (nameStart + nameLength > data.size) break
            val name = decodeName(data, nameStart, nameLength)

            val recordLength = 38 + impUseLength + nameLength
            val padded = ((recordLength + 3) / 4) * 4

            val isParent = (characteristics and 0x08) != 0
            val isDeleted = (characteristics and 0x04) != 0
            val isDir = (characteristics and 0x02) != 0
            if (!isParent && !isDeleted && name.isNotEmpty()) {
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val child = readFileEntry(childBlock, childLength)
                if (child != null) {
                    out.add(
                        IsoEntry(
                            path = path,
                            isDirectory = isDir,
                            offset = child.extents.firstOrNull()?.offset ?: 0L,
                            size = child.size,
                            extents = child.extents,
                            inlineData = child.inlineData
                        )
                    )
                    if (isDir) walk(childBlock, childLength, path, depth + 1, out, seen)
                }
            }
            offset += padded
        }
    }

    private data class ParsedEntry(
        val isDirectory: Boolean,
        val size: Long,
        val extents: List<IsoExtent>,
        val inlineData: ByteArray?
    )

    private fun readFileEntry(block: Long, length: Int): ParsedEntry? {
        val blocks = ((length.coerceAtLeast(blockSize)) + blockSize - 1) / blockSize
        val raw = runCatching { readLogicalBlocks(block, blocks.coerceAtLeast(1)) }.getOrNull() ?: return null
        val tag = tagId(raw)
        if (tag != TAG_FILE_ENTRY && tag != TAG_EXTENDED_FILE_ENTRY) return null

        val icbFlags = le16(raw, 16 + 18)
        val fileType = raw[16 + 11].toInt() and 0xFF
        // ECMA-167 file types: 4 = directory, 5 = plain file. Treating 5 as a
        // directory made every file look like one.
        val isDirectory = fileType == 4


        val informationLength = le64(raw, 56)
        val extended = tag == TAG_EXTENDED_FILE_ENTRY
        val lenEaOffset = if (extended) 208 else 168
        val lengthOfEa = le32(raw, lenEaOffset)
        val lengthOfAd = le32(raw, lenEaOffset + 4)
        val adStart = (if (extended) 216 else 176) + lengthOfEa
        if (adStart < 0 || adStart > raw.size) return null

        val adType = icbFlags and 0x07
        if (adType == 3) {
            // Data is embedded directly in the entry.
            val end = (adStart + lengthOfAd).coerceAtMost(raw.size)
            val inline = raw.copyOfRange(adStart.coerceAtMost(end), end)
            return ParsedEntry(isDirectory, inline.size.toLong(), emptyList(), inline)
        }

        val extents = parseAllocationDescriptors(raw, adStart, lengthOfAd, adType)
        return ParsedEntry(isDirectory, informationLength, extents, null)
    }

    private fun parseAllocationDescriptors(
        raw: ByteArray,
        start: Int,
        length: Int,
        adType: Int
    ): List<IsoExtent> {
        val out = mutableListOf<IsoExtent>()
        val step = if (adType == 1) 16 else 8
        var offset = start
        val end = (start + length).coerceAtMost(raw.size)
        var guard = 0
        var buffer = raw
        var localEnd = end
        while (offset + step <= localEnd && guard++ < 8192) {
            val lengthField = le32(buffer, offset)
            val extentLength = lengthField and 0x3FFFFFFF
            val type = (lengthField ushr 30) and 0x3
            val lbn = le32u(buffer, offset + 4)
            when (type) {
                0 -> if (extentLength > 0) {
                    out.add(IsoExtent(logicalToByte(lbn), extentLength.toLong()))
                }
                1, 2 -> { /* allocated-but-unrecorded / unallocated: nothing to read */ }
                3 -> {
                    // Continuation: the next allocation extent lives in its own block.
                    val blocks = ((extentLength + blockSize - 1) / blockSize).coerceAtLeast(1)
                    val cont = runCatching { readLogicalBlocks(lbn, blocks) }.getOrNull() ?: return out
                    // A continuation block starts with an Allocation Extent Descriptor (24 bytes).
                    buffer = cont
                    offset = 24
                    localEnd = (24 + le32(cont, 20)).coerceAtMost(cont.size)
                    continue
                }
            }
            offset += step
        }
        return out
    }

    // ── Low-level helpers ────────────────────────────────────────────────

    private fun findAnchor(): ByteArray? {
        val candidates = mutableListOf(256L)
        val lastSector = source.size / SECTOR - 1
        if (lastSector > 0) {
            candidates.add(lastSector)
            candidates.add(lastSector - 256)
        }
        candidates.add(512L)
        for (sector in candidates) {
            if (sector < 0) continue
            val block = runCatching { source.readAt(sector * SECTOR, SECTOR) }.getOrNull() ?: continue
            if (tagId(block) == 2) return block
        }
        return null
    }

    private fun logicalToByte(logicalBlock: Long): Long =
        (partitionStartBlock + logicalBlock) * blockSize

    private fun readLogicalBlocks(logicalBlock: Long, count: Int): ByteArray =
        source.readAt(logicalToByte(logicalBlock), count * blockSize)

    private fun readExtents(extents: List<IsoExtent>, size: Long, inline: ByteArray?): ByteArray {
        if (inline != null) return inline
        val total = extents.sumOf { it.length }.coerceAtMost(64L * 1024 * 1024).toInt()
        val out = ByteArray(total)
        var written = 0
        for (extent in extents) {
            if (written >= total) break
            val take = minOf(extent.length, (total - written).toLong()).toInt()
            val chunk = source.readAt(extent.offset, take)
            System.arraycopy(chunk, 0, out, written, take)
            written += take
        }
        return out
    }

    private fun decodeName(data: ByteArray, start: Int, length: Int): String {
        if (length <= 0) return ""
        return when (data[start].toInt() and 0xFF) {
            16 -> String(data, start + 1, length - 1, Charsets.UTF_16BE)
            8 -> String(data, start + 1, length - 1, Charsets.ISO_8859_1)
            else -> String(data, start, length, Charsets.ISO_8859_1)
        }.trim()
    }

    private fun dstring(data: ByteArray, start: Int, length: Int): String {
        if (start + length > data.size) return ""
        val declared = data[start + length - 1].toInt() and 0xFF
        val usable = if (declared in 1 until length) declared else length
        return decodeName(data, start, usable)
    }

    private fun tagId(data: ByteArray, offset: Int = 0): Int =
        if (offset + 2 > data.size) -1 else le16(data, offset)

    private fun le16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(d: ByteArray, o: Int): Int =
        ByteBuffer.wrap(d, o, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun le32u(d: ByteArray, o: Int): Long = le32(d, o).toLong() and 0xFFFFFFFFL

    private fun le64(d: ByteArray, o: Int): Long =
        ByteBuffer.wrap(d, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
