package com.yourapp.USBooter.util

/**
 * Shrinks FAT32 and exFAT volumes below their current size without losing files.
 *
 * Neither filesystem has a central catalog of where files live, so every cluster
 * past the new end is first copied into free space earlier in the volume. Then
 * every reference to it is rewritten: the FAT chains, directory entries
 * (including "." / ".." in FAT32 and the stream entries plus set checksums in
 * exFAT), the root-directory pointer and, for exFAT, the allocation bitmap.
 * Only after that is the volume size in the boot sector (and its backup) reduced.
 *
 * Order of writes: data copies first (old clusters stay intact), then the
 * directories, then the FAT/bitmap, then the boot sector. Nothing past the new
 * end is erased, so the files still exist on the drive even if a step fails.
 */
object FatShrink {

    private const val FAT32_MIN_CLUSTERS = 65525L

    /** Smallest safe partition size in device sectors, or null if not computable. */
    fun minimumSectors(device: BlockDevice, start: Long, boot: ByteArray, fs: String): Long? = runCatching {
        when (fs) {
            "FAT32" -> {
                val g = fat32Geo(boot, device.blockSize) ?: return null
                val fat = loadFat(device, start + g.reserved, g.fatSz, g.bps)
                val used = (2L..g.clusters + 1).count { fat[it.toInt()] != 0 }.toLong()
                g.dataStart + maxOf(used, FAT32_MIN_CLUSTERS) * g.spc
            }
            "exFAT" -> {
                val g = exGeo(boot, device.blockSize) ?: return null
                val t = ExTree.read(device, start, g)
                val used = (0 until g.count).count { t.bitmapGet(it) }.toLong()
                g.heap + maxOf(used, 1L) * g.spc
            }
            else -> null
        }
    }.getOrNull()

    /** Shrinks the filesystem to [newSectors]. Returns an error message, or null on success. */
    fun resize(device: BlockDevice, start: Long, newSectors: Long, boot: ByteArray, fs: String): String? =
        try {
            when (fs) {
                "FAT32" -> shrinkFat32(device, start, newSectors, boot)
                "exFAT" -> shrinkExfat(device, start, newSectors, boot)
                else -> "This filesystem cannot be shrunk"
            }
        } catch (e: Exception) {
            "Shrinking stopped because of a drive error: ${e.message}. The files past the cut point were not erased."
        }

    // ------------------------------------------------------------------ FAT32

    private class F32(
        val bps: Int, val spc: Int, val reserved: Long, val fats: Int,
        val fatSz: Long, val root: Long, val total: Long, val fsInfo: Int, val backup: Int
    ) {
        val dataStart get() = reserved + fats * fatSz
        val clusters get() = (total - dataStart) / spc
    }

    private fun fat32Geo(b: ByteArray, blockSize: Int): F32? {
        val bps = le16(b, 11)
        if (bps != blockSize) return null
        val spc = b[13].toInt() and 0xFF
        if (spc == 0) return null
        val small = le16(b, 19).toLong()
        return F32(
            bps, spc, le16(b, 14).toLong(), b[16].toInt() and 0xFF, le32(b, 36), le32(b, 44),
            if (small > 0) small else le32(b, 32), le16(b, 48), le16(b, 50)
        )
    }

    private fun loadFat(device: BlockDevice, lba: Long, sectors: Long, bps: Int): IntArray {
        val out = IntArray((sectors * bps / 4).toInt())
        var s = 0L
        while (s < sectors) {
            val n = minOf(256L, sectors - s).toInt()
            val buf = device.readBlocks(lba + s, n)
            val base = (s * bps / 4).toInt()
            for (i in 0 until buf.size / 4) out[base + i] = le32(buf, i * 4).toInt()
            s += n
        }
        return out
    }

    private fun storeFat(device: BlockDevice, lba: Long, fat: IntArray) {
        val bytes = ByteArray(fat.size * 4)
        for (i in fat.indices) put32(bytes, i * 4, fat[i].toLong() and 0xFFFFFFFFL)
        device.writeBlocks(lba, bytes)
    }

    private fun shrinkFat32(device: BlockDevice, start: Long, newSectors: Long, boot: ByteArray): String? {
        val g = fat32Geo(boot, device.blockSize) ?: return "The FAT32 sector size does not match the drive"
        if (newSectors >= g.total) return null
        val newClusters = (newSectors - g.dataStart) / g.spc
        if (newClusters < FAT32_MIN_CLUSTERS) return "FAT32 cannot be made that small (it needs at least 65525 clusters)"
        val fat = loadFat(device, start + g.reserved, g.fatSz, g.bps)
        val MASK = 0x0FFFFFFF
        val oldMax = (g.clusters + 1).toInt()
        val newMax = (newClusters + 1).toInt()
        fun v(c: Int) = fat[c] and MASK

        val movers = (newMax + 1..oldMax).filter { v(it) != 0 && v(it) != 0x0FFFFFF7 }
        val map = HashMap<Int, Int>()
        var free = 2
        for (c in movers) {
            while (free <= newMax && v(free) != 0) free++
            if (free > newMax) return "There is not enough free space to move the files out of the part being removed"
            map[c] = free
            fat[free] = 0x0FFFFFFF // reserve
            free++
        }
        val cl = g.spc
        fun lba(c: Int) = start + g.dataStart + (c - 2L) * cl
        // 1. copy data
        for ((o, n) in map) device.writeBlocks(lba(n), device.readBlocks(lba(o), cl))
        // 2. remap FAT in memory
        for ((o, n) in map) { fat[n] = fat[o]; fat[o] = 0 }
        for (x in 2..newMax) {
            val t = v(x)
            map[t]?.let { fat[x] = (fat[x] and MASK.inv()) or it }
        }
        for (x in newMax + 1 until fat.size) fat[x] = 0
        // 3. directory entries
        val root = map[g.root.toInt()] ?: g.root.toInt()
        val seen = HashSet<Int>()
        fun chain(first: Int): List<Int> {
            val out = ArrayList<Int>(); var c = first
            while (c in 2..newMax && out.size < 1_000_000 && seen.add(c)) { out += c; c = v(c) }
            return out
        }
        val queue = ArrayDeque<Int>().apply { add(root) }
        while (queue.isNotEmpty()) {
            for (c in chain(queue.removeFirst())) {
                val buf = device.readBlocks(lba(c), cl)
                var changed = false
                var end = false
                for (o in 0 until buf.size step 32) {
                    val first = buf[o].toInt() and 0xFF
                    if (first == 0) { end = true; break }
                    val attr = buf[o + 11].toInt() and 0xFF
                    if (first == 0xE5 || attr == 0x0F || attr and 0x08 != 0) continue
                    val clu = (le16(buf, o + 20) shl 16) or le16(buf, o + 26)
                    val nc = map[clu]
                    if (nc != null) {
                        buf[o + 20] = (nc shr 16).toByte(); buf[o + 21] = (nc shr 24).toByte()
                        buf[o + 26] = nc.toByte(); buf[o + 27] = (nc shr 8).toByte()
                        changed = true
                    }
                    val dot = first == '.'.code
                    if (attr and 0x10 != 0 && !dot) queue.add(nc ?: clu)
                }
                if (changed) device.writeBlocks(lba(c), buf)
                if (end) break
            }
        }
        // 4. FAT copies
        for (i in 0 until g.fats) storeFat(device, start + g.reserved + i * g.fatSz, fat)
        // 5. boot sectors + FSInfo
        put16(boot, 19, 0); put32(boot, 32, newSectors); put32(boot, 44, root.toLong())
        device.writeBlocks(start, boot)
        if (g.backup in 1..31) device.writeBlocks(start + g.backup, boot)
        if (g.fsInfo in 1..31) {
            val freeCount = (2..newMax).count { v(it) == 0 }.toLong()
            for (s in listOf(g.fsInfo, g.fsInfo + 6)) {
                val fi = device.readBlocks(start + s, 1)
                if (le32(fi, 0) == 0x41615252L) {
                    put32(fi, 488, freeCount); put32(fi, 492, 0xFFFFFFFFL)
                    device.writeBlocks(start + s, fi)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ exFAT

    private class ExG(
        val bps: Int, val spc: Int, val fatOff: Long, val fatLen: Long, val heap: Long,
        val count: Int, val root: Int, val volLen: Long
    )

    private fun exGeo(b: ByteArray, blockSize: Int): ExG? {
        val bps = 1 shl (b[108].toInt() and 0xFF)
        if (bps != blockSize) return null
        return ExG(
            bps, 1 shl (b[109].toInt() and 0xFF), le32(b, 80), le32(b, 84), le32(b, 88),
            le32(b, 92).toInt(), le32(b, 96).toInt(), le64(b, 72)
        )
    }

    /** One chained object: a directory, a file stream, the bitmap or the up-case table. */
    private class Obj(
        val dir: Int,          // index of the directory holding the entry (-1 for root)
        val offset: Int,       // byte offset of the stream/bitmap entry in that directory
        val setStart: Int,     // offset of the 0x85 entry (-1 if none)
        val noFatChain: Boolean,
        var clusters: List<Int>,
        val isDir: Boolean,
        val kind: Int          // 0x81 bitmap, 0x82 upcase, 0xC0 stream, 0 root
    ) { var data: ByteArray? = null }

    private class ExTree(val g: ExG, val fat: IntArray, val objs: MutableList<Obj>, var bitmap: ByteArray) {
        fun bitmapGet(i: Int) = i / 8 < bitmap.size && (bitmap[i / 8].toInt() shr (i % 8)) and 1 == 1
        fun bitmapSet(i: Int, on: Boolean) {
            if (i / 8 >= bitmap.size) return
            bitmap[i / 8] = if (on) (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
            else (bitmap[i / 8].toInt() and (1 shl (i % 8)).inv()).toByte()
        }

        companion object {
            fun read(device: BlockDevice, start: Long, g: ExG): ExTree {
                val fat = loadFat(device, start + g.fatOff, g.fatLen, g.bps)
                val csize = g.spc * g.bps
                fun lba(c: Int) = start + g.heap + (c - 2L) * g.spc
                fun fatChain(first: Int): List<Int> {
                    val out = ArrayList<Int>(); var c = first
                    while (c in 2 until g.count + 2 && out.size <= g.count) { out += c; c = fat[c] }
                    return out
                }
                fun readData(cs: List<Int>): ByteArray {
                    val out = ByteArray(cs.size * csize)
                    cs.forEachIndexed { i, c -> device.readBlocks(lba(c), g.spc).copyInto(out, i * csize) }
                    return out
                }
                val objs = mutableListOf<Obj>()
                val root = Obj(-1, 0, -1, false, fatChain(g.root), true, 0)
                objs += root
                var bitmap = ByteArray(0)
                var i = 0
                while (i < objs.size) {
                    val d = objs[i]
                    if (d.isDir) {
                        val buf = readData(d.clusters); d.data = buf
                        var o = 0
                        while (o + 32 <= buf.size) {
                            val t = buf[o].toInt() and 0xFF
                            if (t == 0) break
                            if ((t == 0x81 || t == 0x82) && d.kind == 0) {
                                val first = le32(buf, o + 20).toInt(); val len = le64(buf, o + 24)
                                val cs = fatChain(first)
                                objs += Obj(i, o, -1, false, cs, false, t)
                                if (t == 0x81) bitmap = readData(cs).copyOf(len.toInt())
                            } else if (t == 0x85) {
                                val sec = buf[o + 1].toInt() and 0xFF
                                val isDir = le16(buf, o + 4) and 0x10 != 0
                                val so = o + 32
                                if (sec >= 1 && so + 32 <= buf.size && (buf[so].toInt() and 0xFF) == 0xC0) {
                                    val flags = buf[so + 1].toInt() and 0xFF
                                    val nfc = flags and 0x02 != 0
                                    val first = le32(buf, so + 20).toInt(); val len = le64(buf, so + 24)
                                    val n = ((len + csize - 1) / csize).toInt()
                                    val cs = if (first < 2 || len == 0L) emptyList()
                                        else if (nfc) (first until first + n).toList() else fatChain(first)
                                    objs += Obj(i, so, o, nfc, cs, isDir, 0xC0)
                                }
                                o += 32 * sec
                            }
                            o += 32
                        }
                    }
                    i++
                }
                return ExTree(g, fat, objs, bitmap)
            }
        }
    }

    private fun shrinkExfat(device: BlockDevice, start: Long, newSectors: Long, boot: ByteArray): String? {
        val g = exGeo(boot, device.blockSize) ?: return "The exFAT sector size does not match the drive"
        if (newSectors >= g.volLen) return null
        val newCount = ((newSectors - g.heap) / g.spc).toInt()
        if (newCount < 1) return "exFAT cannot be made that small"
        val t = ExTree.read(device, start, g)
        if (t.bitmap.isEmpty()) return "The exFAT free-space map could not be found"
        val newMax = newCount + 1
        fun lba(c: Int) = start + g.heap + (c - 2L) * g.spc

        val used = (newMax + 1..g.count + 1).filter { t.bitmapGet(it - 2) }
        val map = HashMap<Int, Int>()
        var free = 2
        for (c in used) {
            while (free <= newMax && t.bitmapGet(free - 2)) free++
            if (free > newMax) return "There is not enough free space to move the files out of the part being removed"
            map[c] = free; t.bitmapSet(free - 2, true); free++
        }
        // 1. copy data clusters
        for ((o, n) in map) device.writeBlocks(lba(n), device.readBlocks(lba(o), g.spc))
        for (o in map.keys) t.bitmapSet(o - 2, false)
        // 2. rewrite chains of affected objects
        val fat = t.fat
        for (x in newMax + 1 until minOf(fat.size, g.count + 2)) fat[x] = 0
        for (obj in t.objs) {
            if (obj.clusters.none { it in map }) continue
            val nl = obj.clusters.map { map[it] ?: it }
            for (c in obj.clusters) if (c < fat.size) fat[c] = 0
            nl.forEachIndexed { i, c -> fat[c] = if (i + 1 < nl.size) nl[i + 1] else -1 }
            obj.clusters = nl
            if (obj.dir >= 0) {
                val buf = t.objs[obj.dir].data!!
                put32(buf, obj.offset + 20, nl.first().toLong())
                if (obj.noFatChain) buf[obj.offset + 1] = (buf[obj.offset + 1].toInt() and 0x02.inv()).toByte()
                if (obj.setStart >= 0) stampSetChecksum(buf, obj.setStart)
            }
        }
        // Bitmap length now covers only the remaining clusters.
        val bmLen = (newCount + 7) / 8
        t.bitmap = t.bitmap.copyOf(bmLen)
        for (i in newCount until bmLen * 8) t.bitmapSet(i, false)
        val bm = t.objs.first { it.kind == 0x81 }
        put64(t.objs[bm.dir].data!!, bm.offset + 24, bmLen.toLong())
        // 3. directories and bitmap at their (new) places
        val csize = g.spc * g.bps
        for (obj in t.objs) {
            val data = if (obj.kind == 0x81) t.bitmap else if (obj.isDir) obj.data else null
            data ?: continue
            obj.clusters.forEachIndexed { i, c ->
                val chunk = ByteArray(csize)
                if (i * csize < data.size) data.copyInto(chunk, 0, i * csize, minOf(data.size, (i + 1) * csize))
                device.writeBlocks(lba(c), chunk)
            }
        }
        // 4. FAT
        val fatBytes = device.readBlocks(start + g.fatOff, g.fatLen.toInt())
        for (i in 0 until minOf(fat.size, fatBytes.size / 4)) put32(fatBytes, i * 4, fat[i].toLong() and 0xFFFFFFFFL)
        device.writeBlocks(start + g.fatOff, fatBytes)
        // 5. boot region + backup
        val region = device.readBlocks(start, 12)
        put64(region, 72, newSectors)
        put32(region, 92, newCount.toLong())
        put32(region, 96, (map[g.root] ?: g.root).toLong())
        var chk = 0L
        for (i in 0 until 11 * g.bps) {
            if (i == 106 || i == 107 || i == 112) continue
            chk = (((chk shl 31) or (chk ushr 1)) + (region[i].toLong() and 0xFF)) and 0xFFFFFFFFL
        }
        for (o in 11 * g.bps until 12 * g.bps step 4) put32(region, o, chk)
        device.writeBlocks(start + 12, region)
        device.writeBlocks(start, region)
        return null
    }

    private fun stampSetChecksum(buf: ByteArray, o: Int) {
        val n = 32 * ((buf[o + 1].toInt() and 0xFF) + 1)
        var c = 0
        for (i in 0 until n) {
            if (i == 2 || i == 3) continue
            c = (((c shl 15) or (c ushr 1)) + (buf[o + i].toInt() and 0xFF)) and 0xFFFF
        }
        buf[o + 2] = c.toByte(); buf[o + 3] = (c shr 8).toByte()
    }

    // ---------------------------------------------------------------- helpers

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun le64(b: ByteArray, o: Int): Long =
        (0 until 8).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
    private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
}
