package com.yourapp.USBooter.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes files and directories into a FAT32 partition that [Fat32Formatter] has
 * just created, straight over raw block access. It assumes it is the only writer
 * and that the filesystem starts out empty, which lets it allocate clusters
 * strictly sequentially - so every file lands in one contiguous run and can be
 * written with large multi-sector transfers.
 *
 * Long file names are written as VFAT LFN entry chains with a generated 8.3
 * short name, which is what UEFI firmware and bootloaders expect.
 */
class Fat32Writer(
    private val device: UsbBulkStorageDevice,
    private val partitionStartLba: Long
) {

    /** An open directory whose entries are buffered until [flush]. */
    inner class Dir internal constructor(
        internal val firstCluster: Long,
        internal val isRoot: Boolean,
        internal val parentCluster: Long
    ) {
        internal val entries = mutableListOf<ByteArray>()
        internal val usedShortNames = HashSet<String>()
        internal val children = HashMap<String, Dir>()
    }

    private val bytesPerSector: Int
    private val sectorsPerCluster: Int
    private val reservedSectors: Int
    private val numFats: Int
    private val fatSizeSectors: Long
    private val fatRegionStart: Long
    private val dataRegionStart: Long
    private val totalClusters: Long
    private val rootCluster: Long

    private var nextFreeCluster: Long
    private val dirs = mutableListOf<Dir>()

    /** Cached FAT sector so a contiguous chain doesn't re-read for every cluster. */
    private var fatCacheSector: Long = -1
    private var fatCache: ByteArray? = null
    private var fatCacheDirty = false

    val root: Dir

    init {
        val boot = device.readBlocks(partitionStartLba, 1)
        val buf = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        bytesPerSector = buf.getShort(11).toInt() and 0xFFFF
        sectorsPerCluster = boot[13].toInt() and 0xFF
        reservedSectors = buf.getShort(14).toInt() and 0xFFFF
        numFats = boot[16].toInt() and 0xFF
        fatSizeSectors = buf.getInt(36).toLong() and 0xFFFFFFFFL
        rootCluster = buf.getInt(44).toLong() and 0xFFFFFFFFL
        val totalSectors = buf.getInt(32).toLong() and 0xFFFFFFFFL

        if (bytesPerSector == 0 || sectorsPerCluster == 0 || fatSizeSectors == 0L) {
            throw IOException("Target partition does not contain a FAT32 filesystem")
        }

        fatRegionStart = partitionStartLba + reservedSectors
        dataRegionStart = fatRegionStart + numFats * fatSizeSectors
        totalClusters = (totalSectors - reservedSectors - numFats * fatSizeSectors) / sectorsPerCluster
        nextFreeCluster = rootCluster + 1

        root = Dir(rootCluster, isRoot = true, parentCluster = 0)
        dirs.add(root)

        // The formatter puts an ATTR_VOLUME_ID entry at the start of the root
        // directory so the volume shows its name. flush() rewrites the whole root
        // cluster, so carry that entry over instead of erasing the label.
        val rootStart = runCatching {
            device.readBlocks(dataRegionStart + (rootCluster - 2) * sectorsPerCluster, 1)
        }.getOrNull()
        if (rootStart != null && rootStart.size >= 32 &&
            (rootStart[11].toInt() and 0xFF) == 0x08 &&
            (rootStart[0].toInt() and 0xFF) != 0x00 && (rootStart[0].toInt() and 0xFF) != 0xE5
        ) {
            root.entries.add(rootStart.copyOfRange(0, 32))
        }
    }


    val clusterSizeBytes: Long get() = sectorsPerCluster.toLong() * bytesPerSector

    /** Free space still available for file data, in bytes. */
    fun freeBytes(): Long = ((totalClusters - (nextFreeCluster - rootCluster)) * clusterSizeBytes).coerceAtLeast(0)

    // ── Directory API ────────────────────────────────────────────────────

    /** Creates (or returns) the directory at [path], creating parents as needed. */
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
        val cluster = allocateClusters(1)
        val dir = Dir(cluster, isRoot = false, parentCluster = parent.firstCluster)
        // "." and ".." must be the first two entries of a subdirectory.
        dir.entries.add(dotEntry(".", cluster))
        dir.entries.add(dotEntry("..", if (parent.isRoot) 0 else parent.firstCluster))
        addEntry(parent, name, cluster, sizeBytes = 0, isDirectory = true)
        dirs.add(dir)
        return dir
    }

    // ── File API ─────────────────────────────────────────────────────────

    /**
     * Streams [sizeBytes] into a new file inside [dir]. [read] is called with an
     * offset relative to the start of the file and must return exactly that many
     * bytes. [onBytesWritten] reports incremental progress.
     */
    fun writeFile(
        dir: Dir,
        name: String,
        sizeBytes: Long,
        read: (offset: Long, length: Int) -> ByteArray,
        onBytesWritten: (Long) -> Unit = {}
    ) {
        if (sizeBytes !in 0 until 0x1_0000_0000L) {
            throw IOException("'$name' is $sizeBytes bytes; FAT32 files must be smaller than 4 GiB")
        }
        if (sizeBytes > freeBytes()) {
            throw IOException("Not enough space on the target partition for '$name'")
        }

        var firstCluster = 0L
        if (sizeBytes > 0) {
            val clusterCount = ((sizeBytes + clusterSizeBytes - 1) / clusterSizeBytes)
            firstCluster = allocateClusters(clusterCount)

            val chunkSectors = maxOf(sectorsPerCluster, 128)
            val chunkBytes = chunkSectors.toLong() * bytesPerSector
            var written = 0L
            var lba = clusterToLba(firstCluster)
            while (written < sizeBytes) {
                val remaining = sizeBytes - written
                val take = minOf(chunkBytes, remaining).toInt()
                val payload = read(written, take)
                // Block writes must be sector aligned; pad the tail with zeroes.
                val padded = if (take % bytesPerSector == 0) {
                    payload
                } else {
                    payload.copyOf(((take / bytesPerSector) + 1) * bytesPerSector)
                }
                device.writeBlocks(lba, padded)
                lba += padded.size / bytesPerSector
                written += take
                onBytesWritten(take.toLong())
            }
        }

        addEntry(dir, name, firstCluster, sizeBytes, isDirectory = false)
    }

    /** Writes all buffered directory entries out to the device. Call once at the end. */
    fun flush() {
        // Deepest directories first is not required, but children were allocated
        // before their parents' entries were finalised, so any order is safe here.
        dirs.forEach { writeDirectory(it) }
        flushFatCache()
        updateFsInfo()
    }

    // ── Directory entry construction ─────────────────────────────────────

    private fun addEntry(dir: Dir, name: String, firstCluster: Long, sizeBytes: Long, isDirectory: Boolean) {
        val shortName = generateShortName(name, dir.usedShortNames)
        if (needsLfn(name, shortName)) {
            dir.entries.addAll(buildLfnEntries(name, shortName))
        }
        dir.entries.add(buildShortEntry(shortName, firstCluster, sizeBytes, isDirectory))
    }

    private fun writeDirectory(dir: Dir) {
        val entryBytes = ByteArray(dir.entries.size * 32 + 32) // + terminating free entry
        dir.entries.forEachIndexed { index, entry -> entry.copyInto(entryBytes, index * 32) }

        val clusterBytes = clusterSizeBytes.toInt()
        val clustersNeeded = ((entryBytes.size + clusterBytes - 1) / clusterBytes).coerceAtLeast(1)

        var cluster = dir.firstCluster
        for (i in 0 until clustersNeeded) {
            if (i > 0) {
                val next = allocateClusters(1)
                setFatEntry(cluster, next)
                setFatEntry(next, 0x0FFFFFFFL)
                cluster = next
            }
            val chunk = ByteArray(clusterBytes)
            val from = i * clusterBytes
            if (from < entryBytes.size) {
                entryBytes.copyInto(chunk, 0, from, minOf(entryBytes.size, from + clusterBytes))
            }
            device.writeBlocks(clusterToLba(cluster), chunk)
        }
    }

    private fun dotEntry(name: String, cluster: Long): ByteArray {
        val padded = name.padEnd(11, ' ')
        return buildShortEntry(padded, cluster, 0, isDirectory = true)
    }

    private fun buildShortEntry(shortName: String, firstCluster: Long, sizeBytes: Long, isDirectory: Boolean): ByteArray {
        val entry = ByteArray(32)
        shortName.padEnd(11, ' ').toByteArray(Charsets.US_ASCII).copyInto(entry, 0, 0, 11)
        entry[11] = if (isDirectory) 0x10 else 0x20 // ATTR_DIRECTORY / ATTR_ARCHIVE
        val buf = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(14, fatTime())
        buf.putShort(16, fatDate())
        buf.putShort(18, fatDate())
        buf.putShort(20, ((firstCluster shr 16) and 0xFFFF).toShort())
        buf.putShort(22, fatTime())
        buf.putShort(24, fatDate())
        buf.putShort(26, (firstCluster and 0xFFFF).toShort())
        buf.putInt(28, if (isDirectory) 0 else sizeBytes.toInt())
        return entry
    }

    private fun needsLfn(name: String, shortName: String): Boolean {
        val canonical = shortName.substring(0, 8).trim() +
            shortName.substring(8).trim().let { if (it.isEmpty()) "" else ".$it" }
        return !name.equals(canonical, ignoreCase = true) || name != name.uppercase()
    }

    private fun buildLfnEntries(name: String, shortName: String): List<ByteArray> {
        val checksum = shortNameChecksum(shortName)
        val chars = name.toCharArray()
        val entryCount = (chars.size + 12) / 13
        val out = mutableListOf<ByteArray>()

        for (index in entryCount downTo 1) {
            val entry = ByteArray(32)
            entry[0] = (index or if (index == entryCount) 0x40 else 0x00).toByte()
            entry[11] = 0x0F // ATTR_LONG_NAME
            entry[12] = 0
            entry[13] = checksum
            entry[26] = 0
            entry[27] = 0

            val offsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (i in 0 until 13) {
                val charIndex = (index - 1) * 13 + i
                val value: Int = when {
                    charIndex < chars.size -> chars[charIndex].code
                    charIndex == chars.size -> 0x0000
                    else -> 0xFFFF
                }
                entry[offsets[i]] = (value and 0xFF).toByte()
                entry[offsets[i] + 1] = ((value shr 8) and 0xFF).toByte()
            }
            out.add(entry)
        }
        return out
    }

    private fun shortNameChecksum(shortName: String): Byte {
        var sum = 0
        shortName.padEnd(11, ' ').toByteArray(Charsets.US_ASCII).forEach { b ->
            sum = (((sum and 1) shl 7) + (sum shr 1) + (b.toInt() and 0xFF)) and 0xFF
        }
        return sum.toByte()
    }

    /** Builds a unique 11-character 8.3 name (no dot) for [name]. */
    private fun generateShortName(name: String, used: MutableSet<String>): String {
        val valid = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#\$%&'()-@^_`{}~"
        fun clean(part: String) = part.uppercase().map { if (valid.contains(it)) it else '_' }.joinToString("")

        val dot = name.lastIndexOf('.')
        val rawBase = if (dot > 0) name.substring(0, dot) else name
        val rawExt = if (dot > 0) name.substring(dot + 1) else ""
        val base = clean(rawBase).take(8).ifEmpty { "FILE" }
        val ext = clean(rawExt).take(3)

        var candidate = base.padEnd(8, ' ') + ext.padEnd(3, ' ')
        if (used.contains(candidate)) {
            var counter = 1
            do {
                val suffix = "~$counter"
                val trimmed = base.take((8 - suffix.length).coerceAtLeast(1)) + suffix
                candidate = trimmed.padEnd(8, ' ') + ext.padEnd(3, ' ')
                counter++
            } while (used.contains(candidate) && counter < 100000)
        }
        used.add(candidate)
        return candidate
    }

    // ── Cluster / FAT plumbing ───────────────────────────────────────────

    private fun clusterToLba(cluster: Long): Long =
        dataRegionStart + (cluster - 2) * sectorsPerCluster

    /** Allocates [count] contiguous clusters, chains them, and marks the last EOC. */
    private fun allocateClusters(count: Long): Long {
        if (count <= 0) return 0
        if (nextFreeCluster + count > totalClusters + 2) {
            throw IOException("Target partition is full")
        }
        val first = nextFreeCluster
        for (i in 0 until count) {
            val cluster = first + i
            val value = if (i == count - 1) 0x0FFFFFFFL else cluster + 1
            setFatEntry(cluster, value)
        }
        nextFreeCluster += count
        return first
    }

    private fun setFatEntry(cluster: Long, value: Long) {
        val entriesPerSector = bytesPerSector / 4
        val sector = fatRegionStart + cluster / entriesPerSector
        if (sector != fatCacheSector) {
            flushFatCache()
            fatCache = device.readBlocks(sector, 1)
            fatCacheSector = sector
        }
        val offset = ((cluster % entriesPerSector) * 4).toInt()
        ByteBuffer.wrap(fatCache!!).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value.toInt())
        fatCacheDirty = true
    }

    private fun flushFatCache() {
        val cache = fatCache ?: return
        if (!fatCacheDirty) return
        for (copy in 0 until numFats) {
            device.writeBlocks(fatCacheSector + copy * fatSizeSectors, cache)
        }
        fatCacheDirty = false
    }

    /** Keeps FSInfo's free-cluster hint honest so hosts don't report bogus free space. */
    private fun updateFsInfo() {
        val sector = device.readBlocks(partitionStartLba + 1, 1)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        val free = (totalClusters - (nextFreeCluster - 2)).coerceAtLeast(0)
        buf.putInt(488, free.toInt())
        buf.putInt(492, nextFreeCluster.toInt())
        device.writeBlocks(partitionStartLba + 1, sector)
        device.writeBlocks(partitionStartLba + 7, sector)
    }

    private fun fatTime(): Short {
        val now = java.util.Calendar.getInstance()
        val hours = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minutes = now.get(java.util.Calendar.MINUTE)
        val seconds = now.get(java.util.Calendar.SECOND) / 2
        return ((hours shl 11) or (minutes shl 5) or seconds).toShort()
    }

    private fun fatDate(): Short {
        val now = java.util.Calendar.getInstance()
        val year = (now.get(java.util.Calendar.YEAR) - 1980).coerceIn(0, 127)
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day = now.get(java.util.Calendar.DAY_OF_MONTH)
        return ((year shl 9) or (month shl 5) or day).toShort()
    }
}
