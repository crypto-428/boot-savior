package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * APFS container formatter. A fresh, empty container made by macOS (about 4 KB
 * packed) is adapted to the target size: block counts, chunk-info blocks, the
 * internal pool and the free-queue limits are rewritten, then every touched
 * object gets a new Fletcher-64 checksum. Mirrors tools/adapt-apfs-template.py,
 * which is validated with apfsck from 2 MB to 1 TB.
 *
 * APFS is GPT-only (macOS never puts APFS behind an MBR table).
 */
object ApfsTemplate {

    val APFS_GUID: ByteArray = AppleFs.guidBytes("7C3457EF-0000-11AA-AA11-00306543ECAC")

    private const val RESOURCE = "/apfs/apfs-template.bin.gz"
    private const val BS = 4096
    private const val BPC = 32768
    private const val CPC = 126
    private const val MIN_BLOCKS = 512L
    private const val SM_BLOCK = 23

    fun minimumSectors(sectorSize: Int): Long = MIN_BLOCKS * (BS / sectorSize)

    private fun loadTemplate(): Pair<ByteArray, Int> {
        val raw = (ApfsTemplate::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("APFS template missing from the app"))
            .use { GZIPInputStream(it).readBytes() }
        val blocks = HashMap<Int, ByteArray>()
        var p = 0
        var maxIdx = 0
        while (p + 4 + BS <= raw.size) {
            val idx = ByteBuffer.wrap(raw, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
            blocks[idx] = raw.copyOfRange(p + 4, p + 4 + BS)
            maxIdx = maxOf(maxIdx, idx)
            p += 4 + BS
        }
        val c = ByteArray((maxIdx + 1) * BS)
        blocks.forEach { (i, b) -> b.copyInto(c, i * BS) }
        return c to (maxIdx + 1)
    }

    private fun fletcher(c: ByteArray, block: Int) {
        val o = block * BS
        val m = 0xFFFFFFFFL
        var s1 = 0L
        var s2 = 0L
        var i = o + 8
        while (i < o + BS) {
            s1 = (s1 + u32(c, i)) % m
            s2 = (s2 + s1) % m
            i += 4
        }
        val c1 = m - ((s1 + s2) % m)
        val c2 = m - ((s1 + c1) % m)
        p32(c, o, c1)
        p32(c, o + 4, c2)
    }

    private fun ipLimit(ch: Long): Int { val r = ((3 * (ch + 751) / 1127 - 1) and 0xFFFF).toInt(); return if (r == 2) 3 else r }
    private fun mainLimit(n: Long): Int {
        val r = when {
            n < 0x40000 -> 1 + (n - 1) / 4544
            n < 0x100000 -> 116 + (n - 261281) / 2272
            else -> 512
        }.toInt()
        return if (r == 2) 3 else r
    }

    /** Builds the adapted container image (only the metadata head; the rest is left as-is). */
    fun build(blocks: Long, label: String): ByteArray {
        val (tpl, used) = loadTemplate()
        val n = blocks
        require(n >= maxOf(used + 8L, MIN_BLOCKS)) { "This partition is too small for APFS (at least 2 MB is needed)" }
        val chunks = (n + BPC - 1) / BPC
        val cibs = ((chunks + CPC - 1) / CPC).toInt()
        require(2568 + 8 * cibs + 8 <= BS) { "This partition is too large for APFS here" }
        val c = tpl.copyOf(maxOf(tpl.size, (minOf(n, BPC.toLong()) * BS).toInt()))
        val sm = SM_BLOCK * BS
        var cibAddr = u64(c, sm + 2568).toInt()
        val xid = u64(c, cibAddr * BS + 16)
        var bm = u64(c, cibAddr * BS + 64).toInt()
        val ipOld = u64(c, sm + 176).toInt()
        val ipOldCnt = u64(c, sm + 152).toInt()
        var ipCnt = (3 * (chunks + cibs)).toInt()
        val extra = ArrayList<Int>()
        val ipBase: Int
        if (ipCnt > ipOldCnt) {
            val ipNew = used
            require(ipNew + ipCnt < minOf(n, BPC.toLong())) { "This partition is too large for APFS here" }
            val d = ipNew - ipOld
            for (r in 0 until ipOldCnt) {
                System.arraycopy(c, (ipOld + r) * BS, c, (ipNew + r) * BS, BS)
                java.util.Arrays.fill(c, (ipOld + r) * BS, (ipOld + r + 1) * BS, 0)
                if (u64(c, (ipNew + r) * BS + 8) == (ipOld + r).toLong()) {
                    p64(c, (ipNew + r) * BS + 8, (ipNew + r).toLong()); fletcher(c, ipNew + r)
                }
            }
            cibAddr += d; bm += d
            val ipFqOid = u64(c, sm + 208)
            for (i in 0 until used) {
                val o = i * BS
                if ((u32(c, o + 24) and 0xFFFF) == 2L && u64(c, o + 8) == ipFqOid) {
                    val toff = u16(c, o + 40); val tlen = u16(c, o + 42); val cnt = u32(c, o + 36).toInt()
                    val ks = o + 56 + toff + tlen
                    for (j in 0 until cnt) {
                        val k = u16(c, o + 56 + toff + 4 * j)
                        val pa = u64(c, ks + k + 8)
                        if (pa >= ipOld && pa < ipOld + ipOldCnt) p64(c, ks + k + 8, pa + d)
                    }
                    fletcher(c, i)
                }
            }
            for (b in ipOld until ipOld + ipOldCnt) setBit(c, bm * BS, b, false)
            for (b in ipNew until ipNew + ipCnt) setBit(c, bm * BS, b, true)
            val ipbm = (u64(c, sm + 168) + u16(c, sm + u32(c, sm + 328).toInt())).toInt()
            for (r in ipOldCnt until ipOldCnt + cibs - 1) { extra += ipNew + r; setBit(c, ipbm * BS, r, true) }
            ipBase = ipNew
        } else { ipBase = ipOld; ipCnt = ipOldCnt }

        val c0 = minOf(n, BPC.toLong())
        var pop = 0
        for (i in 0 until BS) pop += Integer.bitCount(c[bm * BS + i].toInt() and 0xFF)
        val free0 = c0 - pop
        val totalFree = free0 + (n - c0)
        val cl = listOf(cibAddr) + extra
        cl.forEachIndexed { k, a ->
            val o = a * BS
            if (k > 0) {
                java.util.Arrays.fill(c, o, o + BS, 0)
                System.arraycopy(c, cibAddr * BS + 8, c, o + 8, 32)
                p64(c, o + 8, a.toLong())
            }
            val cnt = minOf(CPC.toLong(), chunks - k.toLong() * CPC).toInt()
            p32(c, o + 32, k.toLong()); p32(c, o + 36, cnt.toLong())
            for (j in 0 until cnt) {
                val ch = k.toLong() * CPC + j
                val e = o + 40 + j * 32
                val bc = minOf(BPC.toLong(), n - ch * BPC)
                p64(c, e, xid); p64(c, e + 8, ch * BPC); p32(c, e + 16, bc)
                p32(c, e + 20, if (ch == 0L) free0 else bc)
                p64(c, e + 24, if (ch == 0L) bm.toLong() else 0L)
            }
            fletcher(c, a)
        }
        val cu = uuidBytes(); val vu = uuidBytes()
        val maxFs = ((n * BS + (512L shl 20) - 1) / (512L shl 20)).coerceIn(1, 100)
        val name = label.ifBlank { "Untitled" }.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        for (i in 0 until used) {
            val o = i * BS
            when ((u32(c, o + 24) and 0xFFFF).toInt()) {
                1 -> {
                    p64(c, o + 40, n); cu.copyInto(c, o + 72); p32(c, o + 180, maxFs)
                    val low = u64(c, o + 1312) and 0xFFFFFFFFL
                    val min = if (n * BS < (128L shl 20)) mainLimit(n).toLong() else 8L
                    p64(c, o + 1312, (min shl 32) or low)
                }
                5 -> {
                    p64(c, o + 48, n); p64(c, o + 56, chunks); p32(c, o + 64, cibs.toLong()); p32(c, o + 68, 0)
                    p64(c, o + 72, totalFree)
                    p32(c, o + 128, (2568 + 8 * cibs).toLong())
                    p64(c, o + 152, ipCnt.toLong()); p64(c, o + 176, ipBase.toLong())
                    c[o + 224] = ipLimit(chunks).toByte(); c[o + 225] = (ipLimit(chunks) shr 8).toByte()
                    c[o + 264] = mainLimit(n).toByte(); c[o + 265] = (mainLimit(n) shr 8).toByte()
                    cl.forEachIndexed { k, a -> p64(c, o + 2568 + 8 * k, a.toLong()) }
                }
                0x0d -> {
                    vu.copyInto(c, o + 240)
                    java.util.Arrays.fill(c, o + 704, o + 960, 0)
                    name.copyInto(c, o + 704)
                }
                else -> continue
            }
            fletcher(c, i)
        }
        return c.copyOf(maxOf(used, ipBase + ipCnt) * BS)
    }

    /** Writes a fresh APFS container into [sectors] sectors at [start]. */
    fun format(device: BlockWriter, start: Long, sectors: Long, label: String) {
        val ss = device.blockSize
        require(BS % ss == 0) { "APFS needs a sector size that divides 4096" }
        val img = build(sectors / (BS / ss), label)
        val chunk = 256 * 1024
        var off = 0
        while (off < img.size) {
            val len = minOf(chunk, img.size - off)
            device.writeBlocks(start + off / ss, img.copyOfRange(off, off + len))
            off += len
        }
    }

    fun detect(device: BlockDevice, start: Long): String? = runCatching {
        val b = device.readBlocks(start, maxOf(1, 512 / device.blockSize))
        if (String(b, 32, 4, Charsets.US_ASCII) == "NXSB") "APFS" else null
    }.getOrNull()

    private fun uuidBytes(): ByteArray {
        val u = UUID.randomUUID()
        return ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }
    private fun setBit(c: ByteArray, base: Int, bit: Int, on: Boolean) {
        val i = base + bit / 8
        c[i] = if (on) (c[i].toInt() or (1 shl (bit % 8))).toByte() else (c[i].toInt() and (1 shl (bit % 8)).inv()).toByte()
    }
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun u64(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun p32(b: ByteArray, o: Int, v: Long) { ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()) }
    private fun p64(b: ByteArray, o: Int, v: Long) { ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(v) }
}
