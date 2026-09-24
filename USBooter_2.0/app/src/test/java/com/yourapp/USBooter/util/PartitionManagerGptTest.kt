package com.yourapp.USBooter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class PartitionManagerGptTest {

    private val total = 262_144L // 128 MB

    private fun p32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
    private fun p64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (8 * i)).toByte() }
    private fun g32(b: ByteArray, o: Int) = (0 until 4).fold(0L) { a, i -> a or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    /** Empty GPT drive: protective MBR, primary + backup headers, 128 empty entries. */
    private fun emptyGpt(): FakeBlockDevice {
        val d = FakeBlockDevice(total)
        val mbr = ByteArray(512)
        mbr[446 + 4] = 0xEE.toByte(); p32(mbr, 446 + 8, 1); p32(mbr, 446 + 12, total - 1)
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        d.writeBlocks(0, mbr)
        fun header(self: Long, alt: Long, entries: Long) = ByteArray(512).also { h ->
            "EFI PART".toByteArray().copyInto(h); p32(h, 8, 0x00010000); p32(h, 12, 92)
            p64(h, 24, self); p64(h, 32, alt); p64(h, 40, 34); p64(h, 48, total - 34)
            p64(h, 72, entries); p32(h, 80, 128); p32(h, 84, 128)
        }
        val g = PartitionManager.GptTable(header(1, total - 1, 2), 2, 128, 128, ByteArray(128 * 128), total - 1, 34, total - 34)
        d.writeBlocks(total - 1, header(total - 1, 1, total - 33))
        PartitionManager.writeGpt(d, g)
        return d
    }

    private fun crcOk(d: FakeBlockDevice, lba: Long) {
        val h = d.readBlocks(lba, 1)
        val stored = g32(h, 16); p32(h, 16, 0)
        assertEquals(stored, CRC32().apply { update(h, 0, 92) }.value)
        val e = d.readBlocks(g32(h, 72), 32)
        assertEquals(g32(h, 88), CRC32().apply { update(e, 0, 128 * 128) }.value)
    }

    @Test fun gptPartitionsAreListedCreatedResizedAndDeleted() {
        val d = emptyGpt()
        var r = PartitionManager.listDevice(d)
        assertEquals("GPT", r.getString("table"))
        assertTrue(r.getBoolean("editable"))
        assertEquals(0, r.getJSONArray("partitions").length())

        r = PartitionManager.createDevice(d, 2048, 65_536, "NTFS", "DATA")
        assertTrue(r.getBoolean("ok"))
        val parts = r.getJSONArray("partitions")
        assertEquals(1, parts.length())
        assertEquals("NTFS", parts.getJSONObject(0).getString("filesystem"))
        assertEquals(65_536L, parts.getJSONObject(0).getLong("sizeSectors"))
        crcOk(d, 1); crcOk(d, total - 1)

        r = PartitionManager.resizeDevice(d, 1, 100_000, false)
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(100_000L, r.getJSONArray("partitions").getJSONObject(0).getLong("sizeSectors"))
        crcOk(d, 1); crcOk(d, total - 1)

        r = PartitionManager.resizeDevice(d, 1, total, false)
        assertTrue(!r.getBoolean("ok"))

        r = PartitionManager.deleteDevice(d, 1)
        assertEquals(0, r.getJSONArray("partitions").length())
        crcOk(d, 1); crcOk(d, total - 1)
    }
}
