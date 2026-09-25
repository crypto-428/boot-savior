package com.yourapp.USBooter.util

import org.junit.Assert.*
import org.junit.Test

class FatLegacyTest {
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)
    private fun total(b: ByteArray) = le16(b, 19).toLong().let { if (it > 0) it else le32(b, 32) }

    @Test fun fat16FormatsAndResizes() {
        val d = FakeBlockDevice(262144)
        FatLegacyFormatter.format(d, 0, 200000, "DATA", fat12 = false)
        val boot = d.sector(0)
        assertEquals("FAT16", String(boot, 54, 5))
        assertEquals(200000L, total(boot))
        val min = FatLegacyFormatter.minimumSectors(d, 0, boot)!!
        assertTrue(min < 200000)
        assertNull(FatLegacyFormatter.resize(d, 0, 150000, boot))
        assertEquals(150000L, total(d.sector(0)))
        assertNull(FatLegacyFormatter.resize(d, 0, 250000, d.sector(0)))
        assertTrue(total(d.sector(0)) in 150001..250000)
    }

    @Test fun fat12Formats() {
        val d = FakeBlockDevice(16384)
        FatLegacyFormatter.format(d, 0, 16384, "SMALL", fat12 = true)
        assertEquals("FAT12", String(d.sector(0), 54, 5))
    }

    @Test fun fat12RefusesHuge() {
        val d = FakeBlockDevice(4_000_000)
        assertThrows(IllegalArgumentException::class.java) { FatLegacyFormatter.format(d, 0, 4_000_000, "X", true) }
    }
}
