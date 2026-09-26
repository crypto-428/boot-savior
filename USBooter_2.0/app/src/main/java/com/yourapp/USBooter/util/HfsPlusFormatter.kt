package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HFS+ ("Mac OS Extended") formatter written directly over raw USB block access.
 *
 * The layout mirrors what Apple's newfs_hfs / Linux mkfs.hfsplus produce: boot
 * block + volume header in allocation block 0, the allocation bitmap file, then
 * the extents-overflow, attributes and catalog B-trees, an alternate volume
 * header 1024 bytes before the end of the volume, and everything else free.
 *
 * Only the catalog tree carries records on a fresh volume: the root folder and
 * its thread record. The other two trees are empty (header node only), which is
 * exactly what a freshly created Mac volume looks like.
 */
object HfsPlusFormatter {

    /** MBR partition type for an Apple HFS/HFS+ partition. */
    const val MBR_HFS = 0xAF

    /** GPT type GUID "Apple HFS+", as stored on disk. */
    val HFS_GUID: ByteArray = AppleFs.guidBytes("48465300-0000-11AA-AA11-00306543ECAC")

    private const val BLOCK_SIZE = 4096
    private const val CAT_NODE = 4096
    private const val EXT_NODE = 4096
    private const val ATTR_NODE = 8192

    /** Seconds between the HFS epoch (1904-01-01) and the Unix epoch. */
    private const val HFS_EPOCH_DELTA = 2_082_844_800L

    private const val SIGNATURE = 0x482B      // "H+"
    private const val VERSION = 4
    private const val ATTRIBUTES = 0x8000_0100.toInt()  // unmounted + unused-node-fix
    private const val ROOT_FOLDER_ID = 2
    private const val FIRST_USER_CNID = 16

    /** Fixed metadata footprint plus a little slack, in 4 KiB blocks. */
    private fun minimumBlocks(sectors: Long, sectorSize: Int): Long {
        val plan = plan(sectors, sectorSize) ?: return Long.MAX_VALUE
        return plan.metaEnd + 2
    }

    private class Plan(
        val totalBlocks: Long,
        val allocStart: Long, val allocBlocks: Long,
        val extStart: Long, val extBlocks: Long,
        val attrStart: Long, val attrBlocks: Long,
        val catStart: Long, val catBlocks: Long
    ) {
        val metaEnd: Long get() = catStart + catBlocks
    }

    private fun treeBytes(volumeSectors: Long, nodeSize: Int): Long {
        val grain = maxOf(nodeSize, BLOCK_SIZE).toLong()
        val wanted = (volumeSectors * 4)
            .coerceAtLeast(8L * nodeSize)
            .coerceAtMost(64L * 1024 * 1024)
        return ((wanted + grain - 1) / grain) * grain
    }

    private fun plan(sectors: Long, sectorSize: Int): Plan? {
        if (BLOCK_SIZE % sectorSize != 0) return null
        val totalBlocks = sectors * sectorSize / BLOCK_SIZE
        if (totalBlocks < 64) return null
        val volumeSectors = totalBlocks * BLOCK_SIZE / 512
        val bitmapBytes = (totalBlocks + 7) / 8
        val allocBlocks = (bitmapBytes + BLOCK_SIZE - 1) / BLOCK_SIZE
        val extBlocks = treeBytes(volumeSectors, EXT_NODE) / BLOCK_SIZE
        val attrBlocks = treeBytes(volumeSectors, ATTR_NODE) / BLOCK_SIZE
        val catBlocks = treeBytes(volumeSectors, CAT_NODE) / BLOCK_SIZE
        val allocStart = 1L
        val extStart = allocStart + allocBlocks
        val attrStart = extStart + extBlocks
        val catStart = attrStart + attrBlocks
        return Plan(totalBlocks, allocStart, allocBlocks, extStart, extBlocks, attrStart, attrBlocks, catStart, catBlocks)
    }

    /** Smallest partition (in device sectors) an HFS+ volume can occupy. */
    fun minimumSectorsForNew(sectorSize: Int): Long {
        // 16 MB is comfortably above the metadata footprint at every tree size.
        return 16L * 1024 * 1024 / sectorSize
    }

    /**
     * Writes a fresh, empty HFS+ volume into [sectors] device sectors starting at [start].
     */
    fun format(device: BlockDevice, start: Long, sectors: Long, label: String) {
        val sectorSize = device.blockSize
        require(BLOCK_SIZE % sectorSize == 0) { "HFS+ needs a sector size that divides 4096" }
        val p = plan(sectors, sectorSize)
            ?: throw IllegalArgumentException("This partition cannot hold an HFS+ volume")
        require(p.totalBlocks > p.metaEnd + 1) {
            "This partition is too small for HFS+ (at least 16 MB is needed)"
        }
        val name = cleanLabel(label)
        val perBlock = BLOCK_SIZE / sectorSize
        fun writeBlock(index: Long, data: ByteArray) =
            device.writeBlocks(start + index * perBlock, data)

        // ── clear the metadata region ────────────────────────────────────────
        val zero = ByteArray(BLOCK_SIZE * 16)
        var b = 0L
        while (b < p.metaEnd) {
            val chunk = minOf(16L, p.metaEnd - b).toInt()
            writeBlock(b, if (chunk == 16) zero else ByteArray(BLOCK_SIZE * chunk))
            b += chunk
        }

        // ── allocation bitmap ───────────────────────────────────────────────
        val bitmap = ByteArray((p.allocBlocks * BLOCK_SIZE).toInt())
        fun mark(block: Long) {
            val i = (block / 8).toInt()
            bitmap[i] = (bitmap[i].toInt() or (0x80 shr (block % 8).toInt())).toByte()
        }
        for (i in 0 until p.metaEnd) mark(i)
        mark(p.totalBlocks - 1)                     // alternate volume header
        for (i in p.totalBlocks until p.allocBlocks * BLOCK_SIZE * 8) mark(i)  // pad bits
        for (i in 0 until p.allocBlocks) {
            writeBlock(p.allocStart + i, bitmap.copyOfRange((i * BLOCK_SIZE).toInt(), ((i + 1) * BLOCK_SIZE).toInt()))
        }

        // ── empty B-trees ───────────────────────────────────────────────────
        writeBlock(p.extStart, headerNode(EXT_NODE, maxKeyLen = 10, totalNodes = p.extBlocks * BLOCK_SIZE / EXT_NODE,
            usedNodes = 1, clump = p.extBlocks * BLOCK_SIZE, keyCompare = 0, attributes = 0x2, depth = 0, root = 0, leafRecords = 0, firstLeaf = 0, lastLeaf = 0))
        writeAttrNode(::writeBlock, p)

        // ── catalog: header node + one leaf with the root folder ────────────
        val catNodes = p.catBlocks * BLOCK_SIZE / CAT_NODE
        val catHeader = headerNode(CAT_NODE, maxKeyLen = 516, totalNodes = catNodes, usedNodes = 2,
            clump = p.catBlocks * BLOCK_SIZE, keyCompare = 0xCF, attributes = 0x6,
            depth = 1, root = 1, leafRecords = 2, firstLeaf = 1, lastLeaf = 1)
        val catLeaf = catalogLeaf(name)
        val catNodes2 = ByteArray(CAT_NODE * 2)
        catHeader.copyInto(catNodes2, 0)
        catLeaf.copyInto(catNodes2, CAT_NODE)
        writeBlock(p.catStart, catNodes2)

        // ── volume header + alternate copy ──────────────────────────────────
        val now = System.currentTimeMillis() / 1000 + HFS_EPOCH_DELTA
        val vh = volumeHeader(p, now)
        val firstBlock = ByteArray(BLOCK_SIZE)
        vh.copyInto(firstBlock, 1024)
        writeBlock(0, firstBlock)

        val lastBlock = ByteArray(BLOCK_SIZE)
        vh.copyInto(lastBlock, BLOCK_SIZE - 1024)
        writeBlock(p.totalBlocks - 1, lastBlock)

        runCatching { device.synchronizeCache() }
    }

    private fun writeAttrNode(writeBlock: (Long, ByteArray) -> Unit, p: Plan) {
        val node = headerNode(ATTR_NODE, maxKeyLen = 266, totalNodes = p.attrBlocks * BLOCK_SIZE / ATTR_NODE,
            usedNodes = 1, clump = p.attrBlocks * BLOCK_SIZE, keyCompare = 0, attributes = 0x6,
            depth = 0, root = 0, leafRecords = 0, firstLeaf = 0, lastLeaf = 0)
        // the attributes node is 8 KiB: it spans two allocation blocks
        writeBlock(p.attrStart, node.copyOfRange(0, BLOCK_SIZE))
        writeBlock(p.attrStart + 1, node.copyOfRange(BLOCK_SIZE, ATTR_NODE))
    }

    private fun headerNode(
        nodeSize: Int, maxKeyLen: Int, totalNodes: Long, usedNodes: Int, clump: Long,
        keyCompare: Int, attributes: Int, depth: Int, root: Int, leafRecords: Int,
        firstLeaf: Int, lastLeaf: Int
    ): ByteArray {
        val n = ByteArray(nodeSize)
        val b = ByteBuffer.wrap(n).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, 0); b.putInt(4, 0)
        n[8] = 1                     // kBTHeaderNode
        n[9] = 0                     // height
        b.putShort(10, 3)            // numRecords
        b.putShort(14 + 0, depth.toShort())
        b.putInt(14 + 2, root)
        b.putInt(14 + 6, leafRecords)
        b.putInt(14 + 10, firstLeaf)
        b.putInt(14 + 14, lastLeaf)
        b.putShort(14 + 18, nodeSize.toShort())
        b.putShort(14 + 20, maxKeyLen.toShort())
        b.putInt(14 + 22, totalNodes.toInt())
        b.putInt(14 + 26, (totalNodes - usedNodes).toInt())
        b.putInt(14 + 32, clump.coerceAtMost(0xFFFFFFFFL).toInt())
        n[14 + 36] = 0                            // btreeType: control file
        n[14 + 37] = keyCompare.toByte()
        b.putInt(14 + 38, attributes)
        // node-used bitmap
        var mapByte = 0
        for (i in 0 until usedNodes) mapByte = mapByte or (0x80 shr i)
        n[248] = mapByte.toByte()
        // record offsets, stored from the end of the node backwards
        b.putShort((nodeSize - 2), 14)
        b.putShort((nodeSize - 4), 120)
        b.putShort((nodeSize - 6), 248)
        b.putShort((nodeSize - 8), (nodeSize - 8).toShort())
        return n
    }

    private fun catalogLeaf(name: String): ByteArray {
        val n = ByteArray(CAT_NODE)
        val b = ByteBuffer.wrap(n).order(ByteOrder.BIG_ENDIAN)
        n[8] = 0xFF.toByte()          // kBTLeafNode (-1)
        n[9] = 1                      // height
        b.putShort(10, 2)             // numRecords

        val chars = name.toCharArray()
        val nameLen = chars.size
        val now = System.currentTimeMillis() / 1000 + HFS_EPOCH_DELTA

        // record 0: the root folder, keyed by (parent = 1, volume name)
        var o = 14
        val keyLen = 2 + 2 + 2 * nameLen + 2   // parentID + nameLength + name  (keyLength field excluded)
        b.putShort(o, (4 + 2 + 2 * nameLen).toShort())
        b.putInt(o + 2, 1)                     // parent of the root folder
        b.putShort(o + 6, nameLen.toShort())
        for (i in 0 until nameLen) b.putChar(o + 8 + 2 * i, chars[i])
        var f = o + 8 + 2 * nameLen
        b.putShort(f, 1)                       // kHFSPlusFolderRecord
        b.putShort(f + 2, 0)                   // flags
        b.putInt(f + 4, 0)                     // valence
        b.putInt(f + 8, ROOT_FOLDER_ID)
        b.putInt(f + 12, now.toInt())          // createDate
        b.putInt(f + 16, now.toInt())          // contentModDate
        b.putShort(f + 32 + 10, 0x41ED)        // fileMode 040755
        b.putInt(f + 80, 0)                    // textEncoding
        b.putInt(f + 84, 0)                    // folderCount
        val rec0End = f + 88

        // record 1: the root folder's thread record
        o = rec0End
        b.putShort(o, 6)                       // keyLength: parentID + nameLength
        b.putInt(o + 2, ROOT_FOLDER_ID)
        b.putShort(o + 6, 0)
        f = o + 8
        b.putShort(f, 3)                       // kHFSPlusFolderThreadRecord
        b.putShort(f + 2, 0)
        b.putInt(f + 4, 1)                     // parentID of the root folder
        b.putShort(f + 8, nameLen.toShort())
        for (i in 0 until nameLen) b.putChar(f + 10 + 2 * i, chars[i])
        val rec1End = f + 10 + 2 * nameLen

        b.putShort(CAT_NODE - 2, 14)
        b.putShort(CAT_NODE - 4, rec0End.toShort())
        b.putShort(CAT_NODE - 6, rec1End.toShort())
        require(keyLen > 0)
        return n
    }

    private fun volumeHeader(p: Plan, now: Long): ByteArray {
        val v = ByteArray(512)
        val b = ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN)
        b.putShort(0, SIGNATURE.toShort())
        b.putShort(2, VERSION.toShort())
        b.putInt(4, ATTRIBUTES)
        "10.0".toByteArray(Charsets.US_ASCII).copyInto(v, 8)
        b.putInt(12, 0)                        // journalInfoBlock: not journalled
        b.putInt(16, now.toInt())              // createDate
        b.putInt(20, now.toInt())              // modifyDate
        b.putInt(24, 0)                        // backupDate
        b.putInt(28, now.toInt())              // checkedDate
        b.putInt(32, 0)                        // fileCount
        b.putInt(36, 0)                        // folderCount
        b.putInt(40, BLOCK_SIZE)
        b.putInt(44, p.totalBlocks.toInt())
        b.putInt(48, (p.totalBlocks - p.metaEnd - 1).toInt())   // freeBlocks
        b.putInt(52, p.metaEnd.toInt())                         // nextAllocation
        b.putInt(56, 65536)                    // rsrcClumpSize
        b.putInt(60, 65536)                    // dataClumpSize
        b.putInt(64, FIRST_USER_CNID)
        b.putInt(68, 0)                        // writeCount
        b.putLong(72, 1L)                      // encodingsBitmap: MacRoman

        fun fork(off: Int, startBlock: Long, blocks: Long, nodeSize: Int) {
            val bytes = blocks * BLOCK_SIZE
            b.putLong(off, bytes)
            b.putInt(off + 8, bytes.coerceAtMost(0xFFFFFFFFL).toInt())
            b.putInt(off + 12, blocks.toInt())
            b.putInt(off + 16, startBlock.toInt())
            b.putInt(off + 20, blocks.toInt())
            require(nodeSize > 0)
        }
        fork(112, p.allocStart, p.allocBlocks, 1)
        fork(192, p.extStart, p.extBlocks, EXT_NODE)
        fork(272, p.catStart, p.catBlocks, CAT_NODE)
        fork(352, p.attrStart, p.attrBlocks, ATTR_NODE)
        // startup file stays empty
        return v
    }

    private fun cleanLabel(label: String): String {
        val trimmed = label.trim().replace('/', '_').replace(':', '_')
        val kept = trimmed.filter { it.code in 32..0xFFFF && it != '\uFFFD' }.take(60)
        return kept.ifBlank { "Untitled" }
    }

    // ── existing volumes ────────────────────────────────────────────────────

    /** Reads an existing HFS+ volume header, or null if there isn't one. */
    fun readHeader(device: BlockDevice, start: Long): Header? = runCatching {
        val perBlock = (BLOCK_SIZE / device.blockSize).coerceAtLeast(1)
        val head = device.readBlocks(start, perBlock)
        if (head.size < 1536) return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        val sig = b.getShort(1024).toInt() and 0xFFFF
        if (sig != SIGNATURE && sig != 0x4858) return null   // "H+" or "HX"
        Header(
            hfsx = sig == 0x4858,
            blockSize = b.getInt(1024 + 40),
            totalBlocks = b.getInt(1024 + 44).toLong() and 0xFFFFFFFFL,
            freeBlocks = b.getInt(1024 + 48).toLong() and 0xFFFFFFFFL,
            bitmapBlocks = b.getInt(1024 + 112 + 12).toLong() and 0xFFFFFFFFL
        )
    }.getOrNull()

    class Header(val hfsx: Boolean, val blockSize: Int, val totalBlocks: Long, val freeBlocks: Long, val bitmapBlocks: Long)

    fun detect(device: BlockDevice, start: Long): String? =
        readHeader(device, start)?.let { if (it.hfsx) "HFSX" else "HFS+" }

    /** Sectors the volume header says the volume spans (0 if unknown). */
    fun declaredSectors(device: BlockDevice, start: Long): Long {
        val h = readHeader(device, start) ?: return 0
        return h.totalBlocks * h.blockSize / device.blockSize
    }

    /** Smallest size this volume can shrink to without losing data. */
    fun minimumSectors(device: BlockDevice, start: Long): Long {
        val h = readHeader(device, start) ?: return 0
        val used = lastUsedBlock(device, start, h) + 1
        return (used.coerceAtLeast(64) + 1) * h.blockSize / device.blockSize
    }

    /** Largest size the existing allocation bitmap can address. */
    fun maximumSectors(device: BlockDevice, start: Long): Long {
        val h = readHeader(device, start) ?: return 0
        return h.bitmapBlocks * h.blockSize * 8 * h.blockSize / device.blockSize
    }

    private fun lastUsedBlock(device: BlockDevice, start: Long, h: Header): Long {
        val perBlock = (h.blockSize / device.blockSize).coerceAtLeast(1)
        var last = 0L
        for (i in 0 until h.bitmapBlocks) {
            val data = runCatching { device.readBlocks(start + (1 + i) * perBlock, perBlock) }.getOrNull() ?: break
            for (j in data.indices.reversed()) {
                val byte = data[j].toInt() and 0xFF
                if (byte != 0) {
                    var bit = 0
                    for (k in 0..7) if (byte and (0x80 shr k) != 0) bit = k
                    val block = (i * h.blockSize + j) * 8L + bit
                    // the last bit set in this bitmap block
                    var highest = 0
                    for (k in 0..7) if (byte and (0x80 shr k) != 0) highest = k
                    val cand = (i * h.blockSize + j) * 8L + highest
                    if (cand < h.totalBlocks && cand > last) last = cand
                    if (block >= 0) break
                }
            }
        }
        return last
    }

    /**
     * Resizes an existing HFS+ volume in place. Only the volume header, its
     * alternate copy and the bitmap tail change, so files are never moved.
     * Returns an error message, or null on success.
     */
    fun resize(device: BlockDevice, start: Long, newSectors: Long): String? {
        val h = readHeader(device, start) ?: return "This partition does not hold an HFS+ volume."
        val perBlock = (h.blockSize / device.blockSize).coerceAtLeast(1)
        val newBlocks = newSectors * device.blockSize / h.blockSize
        if (newBlocks < 64) return "The new size is too small for an HFS+ volume."
        if (newBlocks > h.bitmapBlocks * h.blockSize * 8L) {
            return "HFS+ cannot grow this far: the volume's free-space map only covers " +
                "${h.bitmapBlocks * h.blockSize * 8L * h.blockSize / (1024 * 1024)} MB."
        }
        val lastUsed = lastUsedBlock(device, start, h)
        if (newBlocks <= lastUsed + 1) {
            return "HFS+ cannot shrink that far: the volume still stores data near its end."
        }

        // rewrite the bitmap tail: free the blocks that stay, mark the rest used
        val bitmap = ByteArray((h.bitmapBlocks * h.blockSize).toInt())
        for (i in 0 until h.bitmapBlocks) {
            val data = device.readBlocks(start + (1 + i) * perBlock, perBlock)
            data.copyInto(bitmap, (i * h.blockSize).toInt(), 0, h.blockSize)
        }
        fun set(block: Long, used: Boolean) {
            val idx = (block / 8).toInt()
            val mask = 0x80 shr (block % 8).toInt()
            bitmap[idx] = if (used) (bitmap[idx].toInt() or mask).toByte()
            else (bitmap[idx].toInt() and mask.inv()).toByte()
        }
        val oldLast = h.totalBlocks - 1
        set(oldLast, false)                                  // old alternate header slot
        val capacity = h.bitmapBlocks * h.blockSize * 8L
        for (blk in minOf(h.totalBlocks, newBlocks) until capacity) set(blk, blk >= newBlocks)
        set(newBlocks - 1, true)                             // new alternate header slot
        var used = 0L
        for (blk in 0 until newBlocks) {
            if (bitmap[(blk / 8).toInt()].toInt() and (0x80 shr (blk % 8).toInt()) != 0) used++
        }
        for (i in 0 until h.bitmapBlocks) {
            device.writeBlocks(
                start + (1 + i) * perBlock,
                bitmap.copyOfRange((i * h.blockSize).toInt(), ((i + 1) * h.blockSize).toInt())
            )
        }

        // patch both volume headers
        val first = device.readBlocks(start, perBlock)
        val b = ByteBuffer.wrap(first).order(ByteOrder.BIG_ENDIAN)
        b.putInt(1024 + 44, newBlocks.toInt())
        b.putInt(1024 + 48, (newBlocks - used).toInt())
        b.putInt(1024 + 52, 0)
        device.writeBlocks(start, first)

        val vh = first.copyOfRange(1024, 1536)
        val altBlock = (newBlocks * h.blockSize - 1024) / h.blockSize
        val altOffset = ((newBlocks * h.blockSize - 1024) % h.blockSize).toInt()
        val tail = device.readBlocks(start + altBlock * perBlock, perBlock)
        vh.copyInto(tail, altOffset)
        device.writeBlocks(start + altBlock * perBlock, tail)

        // wipe the stale alternate header so only one copy exists
        if (altBlock != oldLast) {
            val stale = device.readBlocks(start + oldLast * perBlock, perBlock)
            java.util.Arrays.fill(stale, (h.blockSize - 1024), h.blockSize - 512, 0)
            device.writeBlocks(start + oldLast * perBlock, stale)
        }
        runCatching { device.synchronizeCache() }
        return null
    }
}
