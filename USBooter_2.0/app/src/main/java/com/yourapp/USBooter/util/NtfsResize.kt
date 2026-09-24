package com.yourapp.USBooter.util

/**
 * Minimal in-place NTFS resize (like a tiny ntfsresize): changes the volume's cluster count
 * without moving any data. Only allowed down to the last cluster in use, so no file is cut.
 * Updates: boot sector + backup, $Bitmap size (and clears bits past the end), $BadClus:$Bad length.
 */
object NtfsResize {

    class Geometry(
        val bps: Int, val spc: Int, val clusterBytes: Int, val mftLcn: Long, val recSize: Int, val scale: Long
    )

    fun geometry(boot: ByteArray, blockSize: Int): Geometry? {
        if (String(boot, 3, 8, Charsets.US_ASCII) != "NTFS    ") return null
        val bps = le16(boot, 11)
        val spc = boot[13].toInt() and 0xFF
        if (bps !in 512..4096 || bps % blockSize != 0 || spc == 0) return null
        val cb = bps * spc
        val r = boot[64].toInt()
        val rec = if (r < 0) 1 shl (-r) else r * cb
        if (rec !in 512..4096) return null
        return Geometry(bps, spc, cb, le64(boot, 48), rec, (bps / blockSize).toLong())
    }

    /** Smallest partition size in device sectors that keeps every used cluster, or null if unreadable. */
    fun minimumSectors(device: BlockDevice, start: Long, boot: ByteArray): Long? = runCatching {
        val g = geometry(boot, device.blockSize) ?: return null
        val (rec, bits) = readBitmap(device, start, g)
        val own = bitmapRuns(rec.second)
        // Bits past the current cluster count are padding (always set), so ignore them.
        val current = minOf(le64(boot, 40) / g.spc, bits.size * 8L)
        var last = -1L
        var c = current - 1
        while (c >= 0) {
            val used = (bits[(c / 8).toInt()].toInt() shr (c % 8).toInt()) and 1 != 0
            if (used && own.none { c >= it.first && c < it.first + it.second }) { last = c; break }
            c--
        }
        // The free-space map lives at the volume's end, so leave room to move it.
        val clusters = maxOf(last + 1, 1L) + bitmapClustersFor(current, g) + 1
        // +1 sector for the backup boot sector, then round up to 1 MiB.
        val sectors = clusters * g.spc * g.scale + 1
        val mib = 1_048_576L / device.blockSize
        ((sectors + mib - 1) / mib) * mib
    }.getOrNull()

    /** Resizes the filesystem to fit [newSectors] device sectors. Returns an error message or null. */
    fun resize(device: BlockDevice, start: Long, oldSectors: Long, newSectors: Long, boot: ByteArray): String? {
        val g = geometry(boot, device.blockSize) ?: return "The NTFS boot sector could not be read"
        val volSectors = (newSectors - 1) / g.scale           // NTFS sectors; last device sector keeps the backup
        val clusters = volSectors / g.spc
        val (bitmapRec, bits) = readBitmap(device, start, g)
        val min = minimumSectors(device, start, boot) ?: return "The NTFS free-space map could not be read"
        if (newSectors < min) return "NTFS files reach up to ${min} sectors; the volume cannot be made smaller than that"

        val bitmapBytes = ((clusters + 63) / 64) * 8
        val bmAttr = findAttr(bitmapRec.second, 0x80, null) ?: return "The NTFS \$Bitmap record is damaged"
        val alloc = le64(bitmapRec.second, bmAttr + 0x28)
        if (bitmapBytes > alloc && bitmapRuns(bitmapRec.second).none { it.first + it.second > clusters }) return "The NTFS free-space map has no room to grow; the filesystem keeps its size"

        // $BadClus:$Bad — one sparse run whose length is the cluster count.
        val bad = readRecord(device, start, g, 8)
        val badAttr = findAttr(bad.second, 0x80, "\$Bad") ?: return "The NTFS \$BadClus record is damaged"
        val runOff = badAttr + le16(bad.second, badAttr + 0x20)
        val hdr = bad.second[runOff].toInt() and 0xFF
        val lenBytes = hdr and 0x0F
        if ((hdr shr 4) != 0 || lenBytes == 0) return "The NTFS \$BadClus list is not empty; resize refused"
        if (clusters >= (1L shl (8 * lenBytes - 1))) return "The NTFS \$BadClus list cannot describe the new size"

        // 1. free-space map: clear its old clusters, move it if it would fall past the new end
        val oldClusters = le64(boot, 40) / g.spc
        val rec = bitmapRec.second
        var bmRuns = bitmapRuns(rec)
        val need = bitmapClustersFor(clusters, g)
        fun setBit(c: Long, on: Boolean) {
            val i = (c / 8).toInt(); val m = 1 shl (c % 8).toInt()
            bits[i] = (if (on) bits[i].toInt() or m else bits[i].toInt() and m.inv()).toByte()
        }
        val moving = bmRuns.any { it.first + it.second > clusters }
        if (moving) {
            for ((l, n) in bmRuns) for (c in l until minOf(l + n, oldClusters)) setBit(c, false)
            var run = 0L; var at = -1L; var c = 0L
            while (c < clusters) {
                if ((bits[(c / 8).toInt()].toInt() shr (c % 8).toInt()) and 1 == 0) {
                    run++; if (run == need) { at = c - need + 1; break }
                } else run = 0
                c++
            }
            if (at < 0) return "There is no free space inside the volume to move the NTFS free-space map"
            for (x in at until at + need) setBit(x, true)
            bmRuns = listOf(at to need)
        }
        // new area free, padding past the new end set (NTFS convention), rest zero
        for (x in minOf(oldClusters, clusters) until bits.size * 8L) setBit(x, x >= clusters && x < bitmapBytes * 8)
        val out = if (moving) bits.copyOf((need * g.clusterBytes).toInt()) else bits
        writeBits(device, start, g, bmRuns, out)
        // 2. $Bitmap run list and sizes
        if (moving) {
            val runOffB = bmAttr + le16(rec, bmAttr + 0x20)
            val attrLen = le32(rec, bmAttr + 4).toInt()
            val enc = encodeRun(need, bmRuns[0].first)
            if (runOffB + enc.size + 1 > bmAttr + attrLen) return "The NTFS free-space map record has no room"
            for (i in runOffB until bmAttr + attrLen) rec[i] = 0
            enc.copyInto(rec, runOffB)
            put64(rec, bmAttr + 0x18, need - 1)
            put64(rec, bmAttr + 0x28, need * g.clusterBytes)
        }
        put64(rec, bmAttr + 0x30, bitmapBytes)
        put64(rec, bmAttr + 0x38, bitmapBytes)
        writeRecord(device, start, g, 6, bitmapRec)
        // 3. $BadClus sizes and run length
        val cbytes = clusters * g.clusterBytes
        put64(bad.second, badAttr + 0x18, clusters - 1)
        put64(bad.second, badAttr + 0x28, cbytes)
        put64(bad.second, badAttr + 0x30, cbytes)
        for (i in 0 until lenBytes) bad.second[runOff + 1 + i] = (clusters shr (8 * i)).toByte()
        writeRecord(device, start, g, 8, bad)
        // 4. boot sector + backup at the new end, erase the old backup
        val fixed = boot.copyOf()
        put64(fixed, 40, volSectors)
        device.writeBlocks(start + newSectors - 1, fixed)
        device.writeBlocks(start, fixed)
        val oldCopy = start + oldSectors - 1
        if (oldCopy != start + newSectors - 1 && oldCopy < device.totalBlocks && oldSectors > newSectors) {
            runCatching { device.writeBlocks(oldCopy, ByteArray(device.blockSize)) }
        }
        return null
    }

    // ---------------------------------------------------------------- MFT records

    /** Returns (lba, fixed-up record bytes). Low records live in the first contiguous MFT extent. */
    private fun readRecord(d: BlockDevice, start: Long, g: Geometry, n: Int): Pair<Long, ByteArray> {
        val byteOff = g.mftLcn * g.clusterBytes + n.toLong() * g.recSize
        val lba = start + byteOff / d.blockSize
        val rec = d.readBlocks(lba, g.recSize / d.blockSize)
        if (String(rec, 0, 4, Charsets.US_ASCII) != "FILE") error("MFT record $n is damaged")
        val usa = le16(rec, 4); val cnt = le16(rec, 6)
        for (i in 1 until cnt) {
            val o = i * 512 - 2
            rec[o] = rec[usa + 2 * i]; rec[o + 1] = rec[usa + 2 * i + 1]
        }
        return lba to rec
    }

    private fun writeRecord(d: BlockDevice, start: Long, g: Geometry, n: Int, r: Pair<Long, ByteArray>) {
        val rec = r.second.copyOf()
        val usa = le16(rec, 4); val cnt = le16(rec, 6)
        for (i in 1 until cnt) {
            val o = i * 512 - 2
            rec[usa + 2 * i] = rec[o]; rec[usa + 2 * i + 1] = rec[o + 1]
            rec[o] = rec[usa]; rec[o + 1] = rec[usa + 1]
        }
        d.writeBlocks(r.first, rec)
    }

    private fun findAttr(rec: ByteArray, type: Int, name: String?): Int? {
        var o = le16(rec, 0x14)
        while (o + 16 < rec.size) {
            val t = le32(rec, o); if (t == 0xFFFFFFFFL || t == 0L) return null
            val len = le32(rec, o + 4).toInt(); if (len <= 0) return null
            if (t.toInt() == type && rec[o + 8].toInt() != 0) {
                val nl = rec[o + 9].toInt() and 0xFF
                val nOff = le16(rec, o + 10)
                val n = if (nl == 0) null else String(rec, o + nOff, nl * 2, Charsets.UTF_16LE)
                if (n == name) return o
            }
            o += len
        }
        return null
    }

    private fun runs(rec: ByteArray, attr: Int): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var p = attr + le16(rec, attr + 0x20); var lcn = 0L
        while (p < rec.size) {
            val h = rec[p].toInt() and 0xFF; if (h == 0) break
            val ls = h and 0x0F; val os = h shr 4
            var len = 0L; for (i in 0 until ls) len = len or ((rec[p + 1 + i].toLong() and 0xFF) shl (8 * i))
            var off = 0L; for (i in 0 until os) off = off or ((rec[p + 1 + ls + i].toLong() and 0xFF) shl (8 * i))
            if (os > 0 && (rec[p + ls + os].toInt() and 0x80) != 0) off -= 1L shl (8 * os)
            lcn += off; out += lcn to len
            p += 1 + ls + os
        }
        return out
    }

    private fun readBitmap(d: BlockDevice, start: Long, g: Geometry): Pair<Pair<Long, ByteArray>, ByteArray> {
        val r = readRecord(d, start, g, 6)
        val a = findAttr(r.second, 0x80, null) ?: error("The NTFS \$Bitmap record is damaged")
        val alloc = le64(r.second, a + 0x28).toInt()
        val data = le64(r.second, a + 0x30).toInt()
        val out = ByteArray(alloc)
        var pos = 0
        for ((lcn, len) in runs(r.second, a)) {
            val bytes = (len * g.clusterBytes).toInt()
            val chunk = d.readBlocks(start + lcn * g.clusterBytes / d.blockSize, bytes / d.blockSize)
            chunk.copyInto(out, pos, 0, minOf(bytes, alloc - pos)); pos += bytes
            if (pos >= alloc) break
        }
        for (i in data until alloc) out[i] = 0
        return r to out
    }

    private fun bitmapClustersFor(clusters: Long, g: Geometry): Long {
        val bytes = ((clusters + 63) / 64) * 8
        return (bytes + g.clusterBytes - 1) / g.clusterBytes
    }

    private fun bitmapRuns(rec: ByteArray): List<Pair<Long, Long>> =
        findAttr(rec, 0x80, null)?.let { runs(rec, it) } ?: emptyList()

    private fun writeBits(d: BlockDevice, start: Long, g: Geometry, rl: List<Pair<Long, Long>>, bits: ByteArray) {
        var pos = 0
        for ((lcn, len) in rl) {
            val bytes = (len * g.clusterBytes).toInt()
            val chunk = ByteArray(bytes)
            bits.copyInto(chunk, 0, pos, minOf(bits.size, pos + bytes))
            d.writeBlocks(start + lcn * g.clusterBytes / d.blockSize, chunk); pos += bytes
            if (pos >= bits.size) break
        }
    }

    private fun encodeRun(length: Long, lcn: Long): ByteArray {
        fun bytes(v: Long, signed: Boolean): ByteArray {
            val out = ArrayList<Byte>(); var x = v
            do { out += x.toByte(); x = x shr 8 } while (if (signed) !(x == 0L && out.last() >= 0) && !(x == -1L && out.last() < 0) else x != 0L)
            return out.toByteArray()
        }
        val l = bytes(length, false).let { if (it.last() < 0) it + 0 else it }
        val o = bytes(lcn, true)
        return byteArrayOf(((o.size shl 4) or l.size).toByte()) + l + o
    }

    private fun writeBitmap(d: BlockDevice, start: Long, g: Geometry, rec: ByteArray, a: Int, bits: ByteArray) {
        var pos = 0
        for ((lcn, len) in runs(rec, a)) {
            val bytes = (len * g.clusterBytes).toInt()
            val chunk = ByteArray(bytes)
            bits.copyInto(chunk, 0, pos, minOf(bits.size, pos + bytes))
            d.writeBlocks(start + lcn * g.clusterBytes / d.blockSize, chunk); pos += bytes
            if (pos >= bits.size) break
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun le64(b: ByteArray, o: Int) = (0 until 8).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
}
