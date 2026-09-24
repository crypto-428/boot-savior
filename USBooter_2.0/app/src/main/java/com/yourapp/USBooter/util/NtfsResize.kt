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
        val bits = readBitmap(device, start, g).second
        var last = -1L
        for (i in bits.indices.reversed()) {
            val v = bits[i].toInt() and 0xFF
            if (v != 0) { last = i * 8L + (7 - Integer.numberOfLeadingZeros(v shl 24)); break }
        }
        val clusters = maxOf(last + 1, 1L); System.err.println("NTFSMIN last=$last bytes=${bits.size} spc=${g.spc}")
        // +1 sector for the backup boot sector, then round up to 1 MiB.
        val sectors = clusters * g.spc * g.scale + 1
        val mib = 1_048_576L / device.blockSize
        ((sectors + mib - 1) / mib) * mib
    }.onFailure { System.err.println("NTFSMIN " + it) }.getOrNull()

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
        if (bitmapBytes > alloc) return "The NTFS free-space map has no room to grow; the filesystem keeps its size"

        // $BadClus:$Bad — one sparse run whose length is the cluster count.
        val bad = readRecord(device, start, g, 8)
        val badAttr = findAttr(bad.second, 0x80, "\$Bad") ?: return "The NTFS \$BadClus record is damaged"
        val runOff = badAttr + le16(bad.second, badAttr + 0x20)
        val hdr = bad.second[runOff].toInt() and 0xFF
        val lenBytes = hdr and 0x0F
        if ((hdr shr 4) != 0 || lenBytes == 0) return "The NTFS \$BadClus list is not empty; resize refused"
        if (clusters >= (1L shl (8 * lenBytes - 1))) return "The NTFS \$BadClus list cannot describe the new size"

        // 1. clear bitmap bits past the new end and write the bitmap data back
        for (c in clusters until bits.size * 8L) {
            val i = (c / 8).toInt(); bits[i] = (bits[i].toInt() and (1 shl (c % 8).toInt()).inv()).toByte()
        }
        writeBitmap(device, start, g, bitmapRec.second, bmAttr, bits)
        // 2. $Bitmap sizes
        put64(bitmapRec.second, bmAttr + 0x30, bitmapBytes)
        put64(bitmapRec.second, bmAttr + 0x38, bitmapBytes)
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
