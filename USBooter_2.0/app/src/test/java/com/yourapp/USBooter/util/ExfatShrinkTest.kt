package com.yourapp.USBooter.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExfatShrinkTest {
    private val start = 2048L
    private val count = 4000
    private val fatOff = 24L
    private val fatLen = 32L
    private val heap = fatOff + fatLen
    private val volLen = heap + count

    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
    private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
    private fun le32(b: ByteArray, o: Int) = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun le64(b: ByteArray, o: Int) = (0 until 8).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun lba(c: Long) = start + heap + (c - 2)

    private fun setChecksum(buf: ByteArray, o: Int): Int {
        val n = 32 * ((buf[o + 1].toInt() and 0xFF) + 1)
        var c = 0
        for (i in 0 until n) { if (i == 2 || i == 3) continue; c = (((c shl 15) or (c ushr 1)) + (buf[o + i].toInt() and 0xFF)) and 0xFFFF }
        return c
    }

    private fun fileSet(buf: ByteArray, o: Int, dir: Boolean, first: Long, len: Long, noFat: Boolean) {
        buf[o] = 0x85.toByte(); buf[o + 1] = 2; buf[o + 4] = if (dir) 0x10 else 0x20
        buf[o + 32] = 0xC0.toByte(); buf[o + 33] = (1 or if (noFat) 2 else 0).toByte()
        put32(buf, o + 32 + 20, first); put64(buf, o + 32 + 24, len); put64(buf, o + 32 + 8, len)
        buf[o + 64] = 0xC1.toByte()
        val c = setChecksum(buf, o); buf[o + 2] = c.toByte(); buf[o + 3] = (c shr 8).toByte()
    }

    private fun build(d: FakeBlockDevice): Pair<ByteArray, ByteArray> {
        val region = ByteArray(12 * 512)
        "EXFAT   ".toByteArray().copyInto(region, 3)
        put64(region, 72, volLen); put32(region, 80, fatOff); put32(region, 84, fatLen)
        put32(region, 88, heap); put32(region, 92, count.toLong()); put32(region, 96, 3)
        region[108] = 9; region[109] = 0; region[110] = 1
        region[510] = 0x55; region[511] = 0xAA.toByte()
        d.writeBlocks(start, region); d.writeBlocks(start + 12, region)
        val fat = ByteArray((fatLen * 512).toInt())
        fun link(c: Long, n: Long) = put32(fat, (c * 4).toInt(), n)
        link(0, 0xFFFFFFF8); link(1, 0xFFFFFFFF); link(2, 0xFFFFFFFF); link(3, 0xFFFFFFFF)
        link(3995, 0xFFFFFFFF); link(3999, 3000); link(3000, 0xFFFFFFFF)
        d.writeBlocks(start + fatOff, fat)
        val bm = ByteArray(512)
        fun mark(c: Int) { bm[(c - 2) / 8] = (bm[(c - 2) / 8].toInt() or (1 shl ((c - 2) % 8))).toByte() }
        listOf(2, 3, 3990, 3991, 3992, 3993, 3995, 3999, 3000).forEach(::mark)
        d.writeBlocks(lba(2), bm)
        val root = ByteArray(512)
        root[0] = 0x81.toByte(); put32(root, 20, 2); put64(root, 24, 500)
        fileSet(root, 32, false, 3990, 2000, true)
        fileSet(root, 128, true, 3995, 512, false)
        d.writeBlocks(lba(3), root)
        val sub = ByteArray(512)
        fileSet(sub, 0, false, 3999, 700, false)
        d.writeBlocks(lba(3995), sub)
        val a = ByteArray(2048) { (it * 5).toByte() }
        for (i in 0 until 4) d.writeBlocks(lba(3990L + i), a.copyOfRange(i * 512, i * 512 + 512))
        val b = ByteArray(1024) { (it * 11 + 3).toByte() }
        d.writeBlocks(lba(3999), b.copyOf(512)); d.writeBlocks(lba(3000), b.copyOfRange(512, 1024))
        return a.copyOf(2000) to b.copyOf(700)
    }

    private fun read(d: FakeBlockDevice, buf: ByteArray, so: Int): ByteArray {
        val fat = d.readBlocks(start + fatOff, fatLen.toInt())
        val noFat = buf[so + 1].toInt() and 2 != 0
        var c = le32(buf, so + 20); val len = le64(buf, so + 24).toInt()
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < len) { out.write(d.readBlocks(lba(c), 1)); c = if (noFat) c + 1 else le32(fat, (c * 4).toInt()) }
        return out.toByteArray().copyOf(len)
    }

    @Test fun exfatShrinkMovesFilesAndKeepsThem() {
        val d = FakeBlockDevice(start + volLen + 10)
        val (a, b) = build(d)
        val boot = d.readBlocks(start, 1)
        assertEquals(heap + 9, FatShrink.minimumSectors(d, start, boot, "exFAT"))
        val newSectors = heap + 3100
        assertEquals(null, FatShrink.resize(d, start, newSectors, boot, "exFAT"))
        val nb = d.readBlocks(start, 12)
        assertEquals(newSectors, le64(nb, 72)); assertEquals(3100L, le32(nb, 92))
        assertArrayEquals(nb, d.readBlocks(start + 12, 12))
        val root = d.readBlocks(lba(3), 1)
        assertEquals(setChecksum(root, 32), (root[34].toInt() and 0xFF) or ((root[35].toInt() and 0xFF) shl 8))
        assertEquals(setChecksum(root, 128), (root[130].toInt() and 0xFF) or ((root[131].toInt() and 0xFF) shl 8))
        assertTrue(le32(root, 64 + 20) <= 3101)
        assertArrayEquals(a, read(d, root, 64))
        val subCl = le32(root, 160 + 20)
        assertTrue(subCl <= 3101)
        val sub = d.readBlocks(lba(subCl), 1)
        assertArrayEquals(b, read(d, sub, 32))
        assertEquals(388L, le64(root, 24))
        val bm = d.readBlocks(lba(le32(root, 20)), 1)
        val used = (0 until 3100).count { (bm[it / 8].toInt() shr (it % 8)) and 1 == 1 }
        assertEquals(9, used)
    }

    @Test fun exfatRefusesWhenFilesDoNotFit() {
        val d = FakeBlockDevice(start + volLen + 10)
        build(d)
        assertTrue(FatShrink.resize(d, start, heap + 5, d.readBlocks(start, 1), "exFAT") != null)
    }
}
