package com.yourapp.USBooter.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes files and directories into an exFAT volume that [ExfatFormatter] has
 * just created, over raw block access. This is what makes Windows install media
 * with an `install.wim` larger than 4 GB possible: FAT32 simply cannot hold that
 * file, exFAT can, and Windows Setup reads exFAT natively.
 *
 * Simplifying assumptions (all safe because the volume was created moments ago
 * by our own formatter and nothing else touches it):
 *  - the filesystem is empty, so clusters are handed out strictly sequentially
 *    and every file lands in one contiguous run;
 *  - file data is contiguous, but still receives an explicit FAT chain. Some
 *    WinPE builds used by Windows Setup fail to reopen multi-gigabyte setup
 *    payloads written with NoFatChain, even though that layout is valid exFAT;
 *  - the allocation bitmap is rewritten once at [flush].
 *
 * Names are stored as proper exFAT entry sets: a File entry (0x85), a Stream
 * Extension entry (0xC0) carrying the up-cased name hash, and enough File Name
 * entries (0xC1) to hold the UTF-16 name.
 */
class ExfatWriter(
    private val device: UsbBulkStorageDevice,
    private val partitionStartLba: Long
) {

    /** A directory whose entry sets are buffered in memory until [flush]. */
    inner class Dir internal constructor(
        internal val isRoot: Boolean,
        /** Fixed for the root (the boot sector points at it); assigned at flush otherwise. */
        internal var firstCluster: Long
    ) {
        /** Each element is one complete entry set (file entry + stream + name entries). */
        internal val entrySets = mutableListOf<MutableList<ByteArray>>()
        internal val children = HashMap<String, Dir>()
        /** The stream-extension entry inside the parent that points at this directory. */
        internal var parentStreamEntry: ByteArray? = null
    }

    private val bytesPerSector: Int
    private val sectorsPerCluster: Int
    private val clusterHeapOffset: Long
    private val fatOffset: Long
    private val fatLengthSectors: Long
    private val clusterCount: Long
    private val bitmapFirstCluster: Long
    private val bitmapLengthBytes: Long

    private var nextFreeCluster: Long
    private val dirs = mutableListOf<Dir>()

    private var fatCacheSector = -1L
    private var fatCache: ByteArray? = null
    private var fatCacheDirty = false

    val root: Dir

    init {
        val boot = device.readBlocks(partitionStartLba, 1)
        if (String(boot, 3, 8, Charsets.US_ASCII) != "EXFAT   ") {
            throw IOException("Target partition does not contain an exFAT filesystem")
        }
        val buf = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        fatOffset = buf.getInt(80).toLong() and 0xFFFFFFFFL
        fatLengthSectors = buf.getInt(84).toLong() and 0xFFFFFFFFL
        clusterHeapOffset = buf.getInt(88).toLong() and 0xFFFFFFFFL
        clusterCount = buf.getInt(92).toLong() and 0xFFFFFFFFL
        val rootFirstCluster = buf.getInt(96).toLong() and 0xFFFFFFFFL
        bytesPerSector = 1 shl (boot[108].toInt() and 0xFF)
        sectorsPerCluster = 1 shl (boot[109].toInt() and 0xFF)

        if (bytesPerSector != device.blockSize) {
            throw IOException("exFAT sector size ($bytesPerSector) does not match the drive (${device.blockSize})")
        }

        root = Dir(isRoot = true, firstCluster = rootFirstCluster)
        dirs.add(root)

        // The freshly formatted root holds exactly three entries: volume label,
        // allocation bitmap and up-case table. Keep them; they must stay first.
        val rootCluster = readCluster(rootFirstCluster)
        var bitmapCluster = 0L
        var bitmapBytes = 0L
        var offset = 0
        while (offset + 32 <= rootCluster.size) {
            val type = rootCluster[offset].toInt() and 0xFF
            if (type == 0x00) break
            val entry = rootCluster.copyOfRange(offset, offset + 32)
            root.entrySets.add(mutableListOf(entry))
            if (type == 0x81) {
                val eb = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                bitmapCluster = eb.getInt(20).toLong() and 0xFFFFFFFFL
                bitmapBytes = eb.getLong(24)
            }
            offset += 32
        }
        if (bitmapCluster == 0L) throw IOException("exFAT volume has no allocation bitmap")
        bitmapFirstCluster = bitmapCluster
        bitmapLengthBytes = bitmapBytes

        // The formatter allocates bitmap, up-case table and the single root
        // cluster back to back starting at cluster 2, so the root is last.
        nextFreeCluster = rootFirstCluster + 1
    }

    val clusterSizeBytes: Long get() = sectorsPerCluster.toLong() * bytesPerSector

    fun freeBytes(): Long = ((clusterCount + 2 - nextFreeCluster) * clusterSizeBytes).coerceAtLeast(0)

    // ── Directory API ────────────────────────────────────────────────────

    fun mkdirs(path: String): Dir {
        var current = root
        path.split('/').filter { it.isNotBlank() }.forEach { segment ->
            val key = segment.uppercase()
            current = current.children[key] ?: createDir(current, segment).also {
                current.children[key] = it
            }
        }
        return current
    }

    private fun createDir(parent: Dir, name: String): Dir {
        val dir = Dir(isRoot = false, firstCluster = 0)
        // Cluster and size are patched in at flush, once the entry count is known.
        val set = buildEntrySet(name, firstCluster = 0, sizeBytes = 0, isDirectory = true, noFatChain = false)
        parent.entrySets.add(set)
        dir.parentStreamEntry = set[1]
        dirs.add(dir)
        return dir
    }

    // ── File API ─────────────────────────────────────────────────────────

    /**
     * Streams [sizeBytes] into a new file inside [dir]. [read] must return
     * exactly the number of bytes asked for, at the given offset within the file.
     */
    fun writeFile(
        dir: Dir,
        name: String,
        sizeBytes: Long,
        read: (offset: Long, length: Int) -> ByteArray,
        onBytesWritten: (Long) -> Unit = {}
    ): Long {
        if (sizeBytes > freeBytes()) {
            throw IOException("Not enough space on the exFAT partition for '$name'")
        }

        var firstCluster = 0L
        if (sizeBytes > 0) {
            val clusters = (sizeBytes + clusterSizeBytes - 1) / clusterSizeBytes
            firstCluster = allocateClusters(clusters)

            // Do not depend on NoFatChain for Windows installation payloads.
            // A conventional chain is understood by every exFAT implementation
            // and prevents Setup from reporting a misleading missing-driver error
            // when it cannot reopen sources/install.wim.
            for (i in 0 until clusters) {
                val cluster = firstCluster + i
                setFatEntry(cluster, if (i == clusters - 1) 0xFFFFFFFFL else cluster + 1)
            }

            val chunkBytes = maxOf(clusterSizeBytes, 1L shl 20).let { (it / bytesPerSector) * bytesPerSector }
            var written = 0L
            var lba = clusterToLba(firstCluster)
            while (written < sizeBytes) {
                val take = minOf(chunkBytes, sizeBytes - written).toInt()
                val payload = read(written, take)
                if (payload.size < take) throw IOException("Short read while copying '$name'")
                val padded = if (take % bytesPerSector == 0) payload
                else payload.copyOf(((take / bytesPerSector) + 1) * bytesPerSector)
                device.writeBlocks(lba, padded)
                lba += padded.size / bytesPerSector
                written += take
                onBytesWritten(take.toLong())
            }
        }

        dir.entrySets.add(
            buildEntrySet(name, firstCluster, sizeBytes, isDirectory = false, noFatChain = false)
        )
        return firstCluster
    }

    /** First device LBA of [cluster] - lets callers read a written file back raw. */
    fun lbaOfCluster(cluster: Long): Long = clusterToLba(cluster)

    /** Writes every buffered directory plus the allocation bitmap. Call once at the end. */
    fun flush() {
        // Subdirectories are allocated first so their parents' entries can be
        // patched before any directory is serialised.
        dirs.filter { !it.isRoot }.forEach { dir ->
            val bytesNeeded = (dir.entrySets.sumOf { it.size } + 1) * 32L
            val clusters = ((bytesNeeded + clusterSizeBytes - 1) / clusterSizeBytes).coerceAtLeast(1)
            dir.firstCluster = allocateClusters(clusters)
            for (i in 0 until clusters) {
                val cluster = dir.firstCluster + i
                setFatEntry(cluster, if (i == clusters - 1) 0xFFFFFFFFL else cluster + 1)
            }
            val stream = dir.parentStreamEntry!!
            val buf = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(20, dir.firstCluster.toInt())
            buf.putLong(24, clusters * clusterSizeBytes)
            buf.putLong(8, clusters * clusterSizeBytes) // ValidDataLength
        }

        dirs.forEach { writeDirectory(it) }
        flushFatCache()
        writeAllocationBitmap()
        device.synchronizeCache()
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun writeDirectory(dir: Dir) {
        val entries = dir.entrySets.flatten()
        val payload = ByteArray(entries.size * 32 + 32)
        entries.forEachIndexed { index, entry ->
            // The set checksum can only be computed once every entry is final.
            entry.copyInto(payload, index * 32)
        }
        // Recompute checksums in place over the serialised buffer.
        var index = 0
        dir.entrySets.forEach { set ->
            if ((set[0].toTypeByte()) == 0x85) {
                val checksum = entrySetChecksum(payload, index * 32, set.size)
                payload[index * 32 + 2] = (checksum and 0xFF).toByte()
                payload[index * 32 + 3] = ((checksum shr 8) and 0xFF).toByte()
            }
            index += set.size
        }

        val clusterBytes = clusterSizeBytes.toInt()
        val clustersNeeded = ((payload.size + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)

        var cluster = dir.firstCluster
        for (i in 0 until clustersNeeded) {
            if (i > 0) {
                val next = if (dir.isRoot) {
                    allocateClusters(1).also {
                        setFatEntry(cluster, it)
                        setFatEntry(it, 0xFFFFFFFFL)
                    }
                } else {
                    cluster + 1 // subdirectory runs were allocated contiguously above
                }
                cluster = next
            }
            val chunk = ByteArray(clusterBytes)
            val from = i * clusterBytes
            if (from < payload.size) {
                payload.copyInto(chunk, 0, from, minOf(payload.size, from + clusterBytes))
            }
            device.writeBlocks(clusterToLba(cluster), chunk)
        }
    }

    private fun ByteArray.toTypeByte(): Int = this[0].toInt() and 0xFF

    private fun writeAllocationBitmap() {
        val used = (nextFreeCluster - 2).coerceAtLeast(0)
        if (bitmapLengthBytes * 8 < used) {
            throw IOException("exFAT allocation bitmap is too small for the written data")
        }
        val totalBytes = ((clusterCount + 7) / 8).coerceAtLeast(1)
        val sectorAligned = (((totalBytes + bytesPerSector - 1) / bytesPerSector) * bytesPerSector).toInt()
        val bitmap = ByteArray(sectorAligned)
        for (i in 0 until used) {
            val byteIndex = (i / 8).toInt()
            if (byteIndex >= bitmap.size) break
            bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl (i % 8).toInt())).toByte()
        }
        device.writeBlocks(clusterToLba(bitmapFirstCluster), bitmap)
    }

    private fun allocateClusters(count: Long): Long {
        if (nextFreeCluster + count > clusterCount + 2) {
            throw IOException("exFAT partition is full")
        }
        val first = nextFreeCluster
        nextFreeCluster += count
        return first
    }

    private fun clusterToLba(cluster: Long): Long =
        partitionStartLba + clusterHeapOffset + (cluster - 2) * sectorsPerCluster

    private fun setFatEntry(cluster: Long, value: Long) {
        val byteOffset = cluster * 4
        val sector = partitionStartLba + fatOffset + byteOffset / bytesPerSector
        if (byteOffset / bytesPerSector >= fatLengthSectors) throw IOException("FAT entry out of range")
        if (sector != fatCacheSector) {
            flushFatCache()
            fatCache = device.readBlocks(sector, 1)
            fatCacheSector = sector
        }
        val within = (byteOffset % bytesPerSector).toInt()
        ByteBuffer.wrap(fatCache!!).order(ByteOrder.LITTLE_ENDIAN).putInt(within, value.toInt())
        fatCacheDirty = true
    }

    private fun flushFatCache() {
        val cache = fatCache
        if (cache != null && fatCacheDirty) device.writeBlocks(fatCacheSector, cache)
        fatCacheDirty = false
    }

    private fun readCluster(cluster: Long): ByteArray =
        device.readBlocks(clusterToLba(cluster), sectorsPerCluster)

    // ── Entry construction ───────────────────────────────────────────────

    private fun buildEntrySet(
        rawName: String,
        firstCluster: Long,
        sizeBytes: Long,
        isDirectory: Boolean,
        noFatChain: Boolean
    ): MutableList<ByteArray> {
        val name = sanitize(rawName)
        val chars = name.toCharArray()
        val nameEntries = (chars.size + 14) / 15
        val timestamp = exfatTimestamp()

        val fileEntry = ByteArray(32)
        fileEntry[0] = 0x85.toByte()
        fileEntry[1] = (1 + nameEntries).toByte()
        val fb = ByteBuffer.wrap(fileEntry).order(ByteOrder.LITTLE_ENDIAN)
        fb.putShort(4, (if (isDirectory) 0x0010 else 0x0020).toShort()) // FileAttributes
        fb.putInt(8, timestamp)  // Create
        fb.putInt(12, timestamp) // LastModified
        fb.putInt(16, timestamp) // LastAccessed

        val stream = ByteArray(32)
        stream[0] = 0xC0.toByte()
        stream[1] = (0x01 or (if (noFatChain) 0x02 else 0x00)).toByte() // AllocationPossible [+ NoFatChain]
        stream[3] = chars.size.toByte()
        val sb = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
        sb.putShort(4, nameHash(name).toShort())
        sb.putLong(8, sizeBytes)  // ValidDataLength
        sb.putInt(20, firstCluster.toInt())
        sb.putLong(24, sizeBytes) // DataLength

        val set = mutableListOf(fileEntry, stream)
        for (i in 0 until nameEntries) {
            val entry = ByteArray(32)
            entry[0] = 0xC1.toByte()
            for (j in 0 until 15) {
                val charIndex = i * 15 + j
                val ch = if (charIndex < chars.size) chars[charIndex] else '\u0000'
                entry[2 + j * 2] = (ch.code and 0xFF).toByte()
                entry[3 + j * 2] = ((ch.code shr 8) and 0xFF).toByte()
            }
            set.add(entry)
        }
        return set
    }

    /** exFAT's 16-bit set checksum; bytes 2-3 of the first entry are skipped. */
    private fun entrySetChecksum(data: ByteArray, offset: Int, entryCount: Int): Int {
        var checksum = 0
        for (i in 0 until entryCount * 32) {
            if (i == 2 || i == 3) continue
            val byte = data[offset + i].toInt() and 0xFF
            checksum = (((checksum and 1) shl 15) + (checksum shr 1) + byte) and 0xFFFF
        }
        return checksum
    }

    /**
     * The 16-bit hash of the up-cased name. The mapping must match the up-case
     * table [ExfatFormatter] wrote, which is generated from the same
     * [Character.toUpperCase] over the BMP.
     */
    private fun nameHash(name: String): Int {
        var hash = 0
        name.forEach { ch ->
            val upperCode = Character.toUpperCase(ch.code).let { if (it in 0..0xFFFF) it else ch.code }
            val low = upperCode and 0xFF
            val high = (upperCode shr 8) and 0xFF
            hash = (((hash and 1) shl 15) + (hash shr 1) + low) and 0xFFFF
            hash = (((hash and 1) shl 15) + (hash shr 1) + high) and 0xFFFF
        }
        return hash
    }

    private fun sanitize(name: String): String {
        val invalid = charArrayOf('"', '*', '/', ':', '<', '>', '?', '\\', '|')
        val cleaned = name.map { if (it in invalid || it.code < 0x20) '_' else it }.joinToString("")
        return cleaned.take(255).ifBlank { "FILE" }
    }

    private fun exfatTimestamp(): Int {
        val cal = java.util.Calendar.getInstance()
        val year = (cal.get(java.util.Calendar.YEAR) - 1980).coerceIn(0, 127)
        return (year shl 25) or
            ((cal.get(java.util.Calendar.MONTH) + 1) shl 21) or
            (cal.get(java.util.Calendar.DAY_OF_MONTH) shl 16) or
            (cal.get(java.util.Calendar.HOUR_OF_DAY) shl 11) or
            (cal.get(java.util.Calendar.MINUTE) shl 5) or
            (cal.get(java.util.Calendar.SECOND) / 2)
    }
}
