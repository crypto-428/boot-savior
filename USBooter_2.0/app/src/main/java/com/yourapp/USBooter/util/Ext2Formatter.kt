package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ext2 (rev 1, 4 KiB blocks, filetype feature) formatter written
 * directly over raw USB block access.
 *
 * Why it exists: live-Linux *persistence* needs a Linux filesystem the initrd
 * can mount read-write. Ubuntu/casper looks for a volume labelled `casper-rw`
 * (or `writable`), Debian live for one labelled `persistence` containing a
 * `/persistence.conf` file. Both mount ext2/ext3/ext4 - ext2 is the smallest
 * on-disk format we can create correctly without root, and every live initrd
 * can mount it.
 *
 * The layout produced is the classic one, with a superblock + group descriptor
 * backup in every block group (no sparse_super), a block and inode bitmap and
 * an inode table per group, a root directory with `lost+found`, and optionally
 * one small file in the root (used for `persistence.conf`).
 */
object Ext2Formatter {

    private const val BLOCK_SIZE = 4096
    private const val LOG_BLOCK_SIZE = 2 // 1024 << 2 = 4096
    private const val BLOCKS_PER_GROUP = 32768 // 8 * BLOCK_SIZE bits
    private const val INODES_PER_GROUP = 2048
    private const val INODE_SIZE = 128
    private const val FIRST_INO = 11
    private const val ROOT_INO = 2
    private const val LOST_FOUND_INO = 11
    private const val FILE_INO = 12

    private const val S_IFDIR = 0x4000
    private const val S_IFREG = 0x8000

    /**
     * @param partitionStartLba first 512-byte sector of the partition
     * @param partitionSectorCount partition length in device sectors
     * @param volumeLabel up to 16 characters, e.g. "casper-rw"
     * @param rootFile optional (name, contents) written into the root directory
     */
    fun format(
        device: UsbBulkStorageDevice,
        partitionStartLba: Long,
        partitionSectorCount: Long,
        volumeLabel: String,
        rootFile: Pair<String, ByteArray>? = null
    ) {
        val sectorSize = device.blockSize
        require(BLOCK_SIZE % sectorSize == 0) { "ext2 needs a sector size that divides 4096" }
        val sectorsPerBlock = BLOCK_SIZE / sectorSize

        val fsBlocks = (partitionSectorCount * sectorSize / BLOCK_SIZE)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(fsBlocks > 512) { "The persistence partition is too small for a filesystem" }
        require(rootFile == null || rootFile.second.size <= BLOCK_SIZE) {
            "Only a single-block root file is supported"
        }

        val groups = (fsBlocks + BLOCKS_PER_GROUP - 1) / BLOCKS_PER_GROUP
        val gdtBlocks = (groups * 32 + BLOCK_SIZE - 1) / BLOCK_SIZE
        val inodeTableBlocks = INODES_PER_GROUP * INODE_SIZE / BLOCK_SIZE
        val metaPerGroup = 1 /* sb copy */ + gdtBlocks + 2 /* bitmaps */ + inodeTableBlocks

        // ── Group 0 data blocks: root dir, lost+found dir, optional file ──
        val group0FirstFree = metaPerGroup
        val rootDirBlock = group0FirstFree
        val lostFoundBlock = group0FirstFree + 1
        val fileBlock = if (rootFile != null && rootFile.second.isNotEmpty()) group0FirstFree + 2 else -1
        val group0DataUsed = if (fileBlock >= 0) 3 else 2

        fun writeBlock(index: Int, data: ByteArray) {
            val padded = if (data.size == BLOCK_SIZE) data else data.copyOf(BLOCK_SIZE)
            device.writeBlocks(partitionStartLba + index.toLong() * sectorsPerBlock, padded)
        }

        // ── Group descriptors (identical view for every group) ─────────────
        val gdt = ByteArray(gdtBlocks * BLOCK_SIZE)
        val gdtBuf = ByteBuffer.wrap(gdt).order(ByteOrder.LITTLE_ENDIAN)
        var totalFreeBlocks = 0
        val groupBlockCounts = IntArray(groups)
        for (g in 0 until groups) {
            val groupStart = g * BLOCKS_PER_GROUP
            val groupBlocks = minOf(BLOCKS_PER_GROUP, fsBlocks - groupStart)
            groupBlockCounts[g] = groupBlocks
            val used = metaPerGroup + if (g == 0) group0DataUsed else 0
            val free = groupBlocks - used
            totalFreeBlocks += free
            gdtBuf.position(g * 32)
            gdtBuf.putInt(groupStart + 1 + gdtBlocks)          // block bitmap
            gdtBuf.putInt(groupStart + 2 + gdtBlocks)          // inode bitmap
            gdtBuf.putInt(groupStart + 3 + gdtBlocks)          // inode table
            gdtBuf.putShort(free.toShort())                    // free blocks
            gdtBuf.putShort((if (g == 0) INODES_PER_GROUP - usedInodes(rootFile) else INODES_PER_GROUP).toShort())
            gdtBuf.putShort((if (g == 0) 2 else 0).toShort())  // used dirs
        }

        val totalInodes = INODES_PER_GROUP * groups
        val freeInodes = totalInodes - usedInodes(rootFile)

        // ── Superblock ─────────────────────────────────────────────────────
        val sb = ByteArray(1024)
        val s = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        val now = (System.currentTimeMillis() / 1000).toInt()
        s.putInt(0, totalInodes)
        s.putInt(4, fsBlocks)
        s.putInt(8, 0)                  // reserved blocks
        s.putInt(12, totalFreeBlocks)
        s.putInt(16, freeInodes)
        s.putInt(20, 0)                 // first data block (block size > 1024)
        s.putInt(24, LOG_BLOCK_SIZE)
        s.putInt(28, LOG_BLOCK_SIZE)
        s.putInt(32, BLOCKS_PER_GROUP)
        s.putInt(36, BLOCKS_PER_GROUP)
        s.putInt(40, INODES_PER_GROUP)
        s.putInt(44, now)               // mtime
        s.putInt(48, now)               // wtime
        s.putShort(52, 0)               // mount count
        s.putShort(54, -1)              // max mount count
        s.putShort(56, 0xEF53.toShort())
        s.putShort(58, 1)               // clean
        s.putShort(60, 1)               // errors: continue
        s.putShort(62, 0)
        s.putInt(64, now)               // lastcheck
        s.putInt(68, 0)                 // check interval
        s.putInt(72, 0)                 // creator OS: Linux
        s.putInt(76, 1)                 // rev level: dynamic
        s.putShort(80, 0); s.putShort(82, 0)
        s.putInt(84, FIRST_INO)
        s.putShort(88, INODE_SIZE.toShort())
        s.putShort(90, 0)               // block group nr
        s.putInt(92, 0)                 // feature_compat
        s.putInt(96, 0x0002)            // feature_incompat: filetype
        s.putInt(100, 0)                // feature_ro_compat
        val uuid = java.util.UUID.randomUUID()
        s.position(104)
        s.order(ByteOrder.BIG_ENDIAN)
        s.putLong(uuid.mostSignificantBits); s.putLong(uuid.leastSignificantBits)
        s.order(ByteOrder.LITTLE_ENDIAN)
        val label = volumeLabel.take(16).toByteArray(Charsets.US_ASCII)
        System.arraycopy(label, 0, sb, 120, label.size)

        // ── Write every group's metadata ───────────────────────────────────
        for (g in 0 until groups) {
            val groupStart = g * BLOCKS_PER_GROUP
            val groupBlocks = groupBlockCounts[g]

            // superblock copy: in group 0 it lives at byte 1024 of block 0
            val sbBlock = ByteArray(BLOCK_SIZE)
            ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN).putShort(90, g.toShort())
            if (g == 0) {
                System.arraycopy(sb, 0, sbBlock, 1024, 1024)
            } else {
                System.arraycopy(sb, 0, sbBlock, 0, 1024)
            }
            writeBlock(groupStart, sbBlock)

            // group descriptor table copy
            for (b in 0 until gdtBlocks) {
                writeBlock(groupStart + 1 + b, gdt.copyOfRange(b * BLOCK_SIZE, (b + 1) * BLOCK_SIZE))
            }

            // block bitmap
            val blockBitmap = ByteArray(BLOCK_SIZE)
            val usedInGroup = metaPerGroup + if (g == 0) group0DataUsed else 0
            for (i in 0 until usedInGroup) setBit(blockBitmap, i)
            for (i in groupBlocks until BLOCKS_PER_GROUP) setBit(blockBitmap, i) // past the end
            writeBlock(groupStart + 1 + gdtBlocks, blockBitmap)

            // inode bitmap
            val inodeBitmap = ByteArray(BLOCK_SIZE)
            if (g == 0) for (i in 0 until usedInodes(rootFile)) setBit(inodeBitmap, i)
            for (i in INODES_PER_GROUP until BLOCK_SIZE * 8) setBit(inodeBitmap, i)
            writeBlock(groupStart + 2 + gdtBlocks, inodeBitmap)

            // inode table (zeroed; group 0's first blocks get the real inodes below)
            val empty = ByteArray(BLOCK_SIZE)
            for (b in 0 until inodeTableBlocks) writeBlock(groupStart + 3 + gdtBlocks + b, empty)
        }

        // ── Inodes in group 0 ──────────────────────────────────────────────
        val inodeTableStart = 3 + gdtBlocks
        val table = ByteArray(BLOCK_SIZE) // holds inodes 1..32 (128 bytes each)
        writeInode(table, ROOT_INO, S_IFDIR or 0x1ED, BLOCK_SIZE.toLong(), 3, intArrayOf(rootDirBlock), now)
        writeInode(table, LOST_FOUND_INO, S_IFDIR or 0x1C0, BLOCK_SIZE.toLong(), 2, intArrayOf(lostFoundBlock), now)
        if (fileBlock >= 0) {
            writeInode(
                table, FILE_INO, S_IFREG or 0x1A4, rootFile!!.second.size.toLong(), 1,
                intArrayOf(fileBlock), now
            )
        }
        writeBlock(inodeTableStart, table)

        // ── Directory blocks ───────────────────────────────────────────────
        val root = ByteArray(BLOCK_SIZE)
        var pos = 0
        pos += putDirEntry(root, pos, ROOT_INO, ".", 2)
        pos += putDirEntry(root, pos, ROOT_INO, "..", 2)
        val lastRootEntryPos: Int
        if (fileBlock >= 0) {
            pos += putDirEntry(root, pos, LOST_FOUND_INO, "lost+found", 2)
            lastRootEntryPos = pos
            putDirEntry(root, pos, FILE_INO, rootFile!!.first, 1)
        } else {
            lastRootEntryPos = pos
            putDirEntry(root, pos, LOST_FOUND_INO, "lost+found", 2)
        }
        // the final entry stretches to the end of the block, as ext2 requires
        ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(lastRootEntryPos + 4, (BLOCK_SIZE - lastRootEntryPos).toShort())
        writeBlock(rootDirBlock, root)

        val lost = ByteArray(BLOCK_SIZE)
        var lpos = 0
        lpos += putDirEntry(lost, lpos, LOST_FOUND_INO, ".", 2)
        putDirEntry(lost, lpos, ROOT_INO, "..", 2)
        ByteBuffer.wrap(lost).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(lpos + 4, (BLOCK_SIZE - lpos).toShort())
        writeBlock(lostFoundBlock, lost)

        if (fileBlock >= 0) writeBlock(fileBlock, rootFile!!.second.copyOf(BLOCK_SIZE))

        device.synchronizeCache()
    }

    private fun usedInodes(rootFile: Pair<String, ByteArray>?): Int =
        if (rootFile != null && rootFile.second.isNotEmpty()) FILE_INO else LOST_FOUND_INO

    private fun setBit(bitmap: ByteArray, index: Int) {
        if (index / 8 >= bitmap.size) return
        bitmap[index / 8] = (bitmap[index / 8].toInt() or (1 shl (index % 8))).toByte()
    }

    private fun writeInode(
        table: ByteArray, inode: Int, mode: Int, size: Long, links: Int,
        blocks: IntArray, now: Int
    ) {
        val offset = (inode - 1) * INODE_SIZE
        val b = ByteBuffer.wrap(table, offset, INODE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(mode.toShort())
        b.putShort(0)                       // uid
        b.putInt(size.toInt())
        b.putInt(now); b.putInt(now); b.putInt(now); b.putInt(0)
        b.putShort(0)                       // gid
        b.putShort(links.toShort())
        b.putInt(blocks.size * (BLOCK_SIZE / 512)) // i_blocks, in 512-byte units
        b.putInt(0)                         // flags
        b.putInt(0)                         // osd1
        blocks.forEach { b.putInt(it) }
        repeat(15 - blocks.size) { b.putInt(0) }
    }

    /** Writes one directory entry and returns its record length. */
    private fun putDirEntry(block: ByteArray, offset: Int, inode: Int, name: String, type: Int): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val recLen = ((8 + nameBytes.size) + 3) / 4 * 4
        val b = ByteBuffer.wrap(block, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(inode)
        b.putShort(recLen.toShort())
        b.put(nameBytes.size.toByte())
        b.put(type.toByte())
        System.arraycopy(nameBytes, 0, block, offset + 8, nameBytes.size)
        return recLen
    }
}
