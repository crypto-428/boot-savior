package com.yourapp.USBooter.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only FAT32 walker used to check what actually landed on the
 * pendrive after a file-copy flash. It re-reads the BPB, the FAT and the
 * directory chain straight off the device - nothing is trusted from the writer's
 * in-memory state, which is the whole point of a post-flash verification.
 */
private const val MAX_CLUSTERS = 1 shl 22

class Fat32Reader(
    private val device: UsbBulkStorageDevice,
    private val partitionStartLba: Long
) {
    data class Entry(val name: String, val isDirectory: Boolean, val firstCluster: Long, val size: Long)

    private val bytesPerSector: Int
    private val sectorsPerCluster: Int
    private val reservedSectors: Int
    private val numFats: Int
    private val fatSizeSectors: Long
    private val fatRegionStart: Long
    private val dataRegionStart: Long
    private val rootCluster: Long

    init {
        val boot = device.readBlocks(partitionStartLba, 1)
        val buf = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        bytesPerSector = buf.getShort(11).toInt() and 0xFFFF
        sectorsPerCluster = boot[13].toInt() and 0xFF
        reservedSectors = buf.getShort(14).toInt() and 0xFFFF
        numFats = boot[16].toInt() and 0xFF
        fatSizeSectors = buf.getInt(36).toLong() and 0xFFFFFFFFL
        rootCluster = buf.getInt(44).toLong() and 0xFFFFFFFFL
        if (bytesPerSector == 0 || sectorsPerCluster == 0 || fatSizeSectors == 0L) {
            throw IOException("No readable FAT32 filesystem on the target partition")
        }
        fatRegionStart = partitionStartLba + reservedSectors
        dataRegionStart = fatRegionStart + numFats * fatSizeSectors
    }

    private val clusterBytes: Int get() = sectorsPerCluster * bytesPerSector

    /**
     * Reading the FAT one sector at a time meant one USB round-trip per cluster.
     * On a multi-GB payload that is tens of thousands of SCSI commands and the
     * check appeared frozen. The FAT is now pulled in big chunks and cached, and
     * chains are memoised, which turns the read-back into seconds.
     */
    private val fatChunkSectors = 64
    private val fatCache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }
    private val chainCache = HashMap<Long, MutableList<Long>>()

    private fun fatChunk(sector: Long): ByteArray {
        val chunkIndex = (sector - fatRegionStart) / fatChunkSectors
        val base = fatRegionStart + chunkIndex * fatChunkSectors
        return fatCache.getOrPut(base) {
            val remaining = (fatRegionStart + fatSizeSectors - base).coerceAtMost(fatChunkSectors.toLong())
            device.readBlocks(base, remaining.coerceAtLeast(1).toInt())
        }
    }

    private fun clusterLba(cluster: Long): Long =
        dataRegionStart + (cluster - 2) * sectorsPerCluster

    private fun nextCluster(cluster: Long): Long {
        val byteOffset = cluster * 4
        val sector = fatRegionStart + byteOffset / bytesPerSector
        if (sector >= fatRegionStart + fatSizeSectors) return 0x0FFFFFFFL
        val chunk = fatChunk(sector)
        val chunkBase = ((sector - fatRegionStart) / fatChunkSectors) * fatChunkSectors + fatRegionStart
        val within = ((sector - chunkBase) * bytesPerSector + byteOffset % bytesPerSector).toInt()
        if (within + 4 > chunk.size) return 0x0FFFFFFFL
        return ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(within).toLong() and 0x0FFFFFFFL
    }

    /**
     * Walks the chain lazily, only as far as [needed] clusters, and remembers what
     * it already walked. A cluster that repeats means a damaged FAT, so the walk
     * stops instead of looping forever.
     */
    private fun chain(start: Long, needed: Int = Int.MAX_VALUE): List<Long> {
        val cached = chainCache.getOrPut(start) { mutableListOf() }
        if (cached.size >= needed) return cached
        var c = if (cached.isEmpty()) start else nextCluster(cached.last())
        val seen = HashSet(cached)
        while (c in 2..0x0FFFFFEF && cached.size < needed && cached.size < MAX_CLUSTERS) {
            if (!seen.add(c)) break
            cached.add(c)
            c = nextCluster(c)
        }
        return cached
    }

    private fun readCluster(cluster: Long): ByteArray =
        device.readBlocks(clusterLba(cluster), sectorsPerCluster)

    private val dirCache = HashMap<Long, List<Entry>>()

    /** Entries of the directory whose first cluster is [cluster] (0 = root). */
    fun listDir(cluster: Long = 0): List<Entry> = dirCache.getOrPut(cluster) { readDir(cluster) }

    private fun readDir(cluster: Long): List<Entry> {
        val start = if (cluster == 0L) rootCluster else cluster
        val out = mutableListOf<Entry>()
        val lfn = StringBuilder()
        for (c in chain(start)) {
            val data = readCluster(c)
            var offset = 0
            while (offset + 32 <= data.size) {
                val first = data[offset].toInt() and 0xFF
                if (first == 0x00) return out.toList()
                val attr = data[offset + 11].toInt() and 0xFF
                if (first == 0xE5) { offset += 32; lfn.setLength(0); continue }
                // The volume-label entry is metadata, not a file.
                if ((attr and 0x08) != 0 && (attr and 0x10) == 0 && attr != 0x0F) {
                    offset += 32; lfn.setLength(0); continue
                }
                if (attr == 0x0F) {

                    val part = StringBuilder()
                    intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30).forEach { p ->
                        val ch = ((data[offset + p + 1].toInt() and 0xFF) shl 8) or (data[offset + p].toInt() and 0xFF)
                        if (ch != 0 && ch != 0xFFFF) part.append(ch.toChar())
                    }
                    lfn.insert(0, part)
                    offset += 32
                    continue
                }
                val short = String(data, offset, 11, Charsets.US_ASCII)
                val base = short.substring(0, 8).trim()
                val ext = short.substring(8).trim()
                val shortName = if (ext.isEmpty()) base else "$base.$ext"
                val name = if (lfn.isNotEmpty()) lfn.toString() else shortName
                lfn.setLength(0)
                val hi = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(offset + 20).toLong() and 0xFFFF
                val lo = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(offset + 26).toLong() and 0xFFFF
                val size = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset + 28).toLong() and 0xFFFFFFFFL
                if (name != "." && name != "..") {
                    out.add(Entry(name, (attr and 0x10) != 0, (hi shl 16) or lo, size))
                }
                offset += 32
            }
        }
        return out.toList()
    }

    /** Case-insensitive lookup of a '/'-separated path. */
    fun find(path: String): Entry? {
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var cluster = 0L
        var found: Entry? = null
        parts.forEachIndexed { index, segment ->
            val entry = listDir(cluster).firstOrNull { it.name.equals(segment, ignoreCase = true) } ?: return null
            if (index < parts.lastIndex && !entry.isDirectory) return null
            cluster = entry.firstCluster
            found = entry
        }
        return found
    }

    /** Reads up to [length] bytes of [entry] starting at [offset]. */
    fun read(entry: Entry, offset: Long, length: Int): ByteArray {
        val want = minOf(length.toLong(), (entry.size - offset).coerceAtLeast(0)).toInt()
        val out = ByteArray(want)
        if (want == 0) return out
        val lastNeeded = ((offset + want - 1) / clusterBytes).toInt() + 1
        val clusters = chain(entry.firstCluster, lastNeeded)
        var produced = 0
        var cursor = offset
        while (produced < want) {
            val index = (cursor / clusterBytes).toInt()
            if (index >= clusters.size) break
            val within = (cursor % clusterBytes).toInt()
            val data = readCluster(clusters[index])
            val take = minOf(want - produced, clusterBytes - within, data.size - within)
            if (take <= 0) break
            System.arraycopy(data, within, out, produced, take)
            produced += take
            cursor += take
        }
        return out
    }
}
