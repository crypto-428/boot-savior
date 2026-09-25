package com.yourapp.USBooter.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FatShrinkTest {
    private val start = 2048L
    private val clusters = 70_000L
    private val fatSz = (clusters + 2) * 4 / 512 + 1
    private val dataStart = 32 + 2 * fatSz
    private val total = dataStart + clusters

    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
    private fun le32(b: ByteArray, o: Int) = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun lba(c: Long) = start + dataStart + (c - 2)

    private fun entry(buf: ByteArray, o: Int, name: String, attr: Int, cl: Long, size: Long) {
        name.padEnd(11).toByteArray(Charsets.US_ASCII).copyInto(buf, o)
        buf[o + 11] = attr.toByte()
        buf[o + 20] = (cl shr 16).toByte(); buf[o + 21] = (cl shr 24).toByte()
        buf[o + 26] = cl.toByte(); buf[o + 27] = (cl shr 8).toByte()
        put32(buf, o + 28, size)
    }

    private fun build(d: FakeBlockDevice): Map<String, ByteArray> {
        val boot = ByteArray(512)
        "MSWIN4.1".toByteArray().copyInto(boot, 3)
        boot[11] = 0; boot[12] = 2; boot[13] = 1; boot[14] = 32; boot[16] = 2
        put32(boot, 32, total); put32(boot, 36, fatSz); put32(boot, 44, 2)
        boot[48] = 1; boot[50] = 6
        "FAT32   ".toByteArray().copyInto(boot, 82)
        boot[510] = 0x55; boot[511] = 0xAA.toByte()
        d.writeBlocks(start, boot); d.writeBlocks(start + 6, boot)
        val fi = ByteArray(512); put32(fi, 0, 0x41615252); d.writeBlocks(start + 1, fi)
        val fat = ByteArray((fatSz * 512).toInt())
        fun link(c: Long, n: Long) = put32(fat, (c * 4).toInt(), n)
        link(0, 0x0FFFFFF8); link(1, 0x0FFFFFFF); link(2, 0x0FFFFFFF)
        for (c in 69_990L until 69_995L) link(c, c + 1); link(69_995, 0x0FFFFFFF)
        link(69_999, 0x0FFFFFFF); link(70_001, 0x0FFFFFFF)
        d.writeBlocks(start + 32, fat); d.writeBlocks(start + 32 + fatSz, fat)
        val root = ByteArray(512)
        entry(root, 0, "A       BIN", 0x20, 69_990, 3000)
        entry(root, 32, "SUB", 0x10, 69_999, 0)
        d.writeBlocks(lba(2), root)
        val sub = ByteArray(512)
        entry(sub, 0, ".", 0x10, 69_999, 0); entry(sub, 32, "..", 0x10, 0, 0)
        entry(sub, 64, "B       BIN", 0x20, 70_001, 100)
        d.writeBlocks(lba(69_999), sub)
        val a = ByteArray(3072) { (it * 7).toByte() }
        for (i in 0 until 6) d.writeBlocks(lba(69_990L + i), a.copyOfRange(i * 512, i * 512 + 512))
        val b = ByteArray(512) { (it * 3 + 1).toByte() }
        d.writeBlocks(lba(70_001), b)
        return mapOf("A" to a.copyOf(3000), "B" to b.copyOf(100))
    }

    private fun readChain(d: FakeBlockDevice, first: Long, size: Int): ByteArray {
        val fat = d.readBlocks(start + 32, fatSz.toInt())
        val out = java.io.ByteArrayOutputStream(); var c = first
        while (c in 2..clusters + 1 && out.size() < size) { out.write(d.readBlocks(lba(c), 1)); c = le32(fat, (c * 4).toInt()) and 0x0FFFFFFF }
        return out.toByteArray().copyOf(size)
    }

    private fun cl(b: ByteArray, o: Int) = ((b[o + 20].toLong() and 0xFF) shl 16) or ((b[o + 21].toLong() and 0xFF) shl 24) or
        (b[o + 26].toLong() and 0xFF) or ((b[o + 27].toLong() and 0xFF) shl 8)

    @Test fun fat32ShrinkMovesFilesAndKeepsThem() {
        val d = FakeBlockDevice(start + total + 10)
        val files = build(d)
        val boot = d.readBlocks(start, 1)
        val min = FatShrink.minimumSectors(d, start, boot, "FAT32")!!
        assertEquals(dataStart + 65_525, min)
        val newSectors = dataStart + 66_000
        assertEquals(null, FatShrink.resize(d, start, newSectors, boot, "FAT32"))
        val nb = d.readBlocks(start, 1)
        assertEquals(newSectors, le32(nb, 32))
        assertArrayEquals(nb, d.readBlocks(start + 6, 1))
        val root = d.readBlocks(lba(2), 1)
        val aCl = cl(root, 0); val subCl = cl(root, 32)
        assertTrue(aCl <= 66_001 && subCl <= 66_001)
        assertArrayEquals(files["A"], readChain(d, aCl, 3000))
        val sub = d.readBlocks(lba(subCl), 1)
        assertEquals(subCl, cl(sub, 0))
        val bCl = cl(sub, 64)
        assertTrue(bCl <= 66_001)
        assertArrayEquals(files["B"], readChain(d, bCl, 100))
        val fat = d.readBlocks(start + 32, fatSz.toInt())
        for (c in 66_002L..clusters + 1) assertEquals(0L, le32(fat, (c * 4).toInt()))
        assertArrayEquals(fat, d.readBlocks(start + 32 + fatSz, fatSz.toInt()))
    }

    @Test fun fat32RefusesBelowMinimum() {
        val d = FakeBlockDevice(start + total + 10)
        build(d)
        val boot = d.readBlocks(start, 1)
        assertTrue(FatShrink.resize(d, start, dataStart + 60_000, boot, "FAT32") != null)
    }
}
