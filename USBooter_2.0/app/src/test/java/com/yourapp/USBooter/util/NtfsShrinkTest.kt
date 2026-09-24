package com.yourapp.USBooter.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfsShrinkTest {
    @Test fun ntfsPartitionCanBeShrunkAndGrownBack() {
        val total = 262_144L
        val d = FakeBlockDevice(total)
        val mbr = ByteArray(512); mbr[510] = 0x55; mbr[511] = 0xAA.toByte(); d.writeBlocks(0, mbr)
        var r = PartitionManager.createDevice(d, 2048, 200_000, "NTFS", "DATA")
        assertTrue(r.toString(), r.getBoolean("ok"))
        val p = r.getJSONArray("partitions").getJSONObject(0)
        val min = p.getLong("minSizeSectors")
        assertTrue("min $min", min in 2..100_000)
        r = PartitionManager.resizeDevice(d, 1, 100_000, false)
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(100_000L, r.getJSONArray("partitions").getJSONObject(0).getLong("sizeSectors"))
        val boot = d.readBlocks(2048, 1)
        val vol = (0 until 8).fold(0L) { a, i -> a or ((boot[40 + i].toLong() and 0xFF) shl (8 * i)) }
        assertEquals(99_999L, vol)
        assertTrue(d.readBlocks(2048 + 99_999, 1).contentEquals(boot))
        System.getenv("NTFS_DUMP")?.let { path ->
            java.io.File(path).outputStream().use { o -> for (l in 2048L until 2048 + 100_000) o.write(d.readBlocks(l, 1)) }
        }
        r = PartitionManager.resizeDevice(d, 1, min - 2048, false)
        assertTrue(!r.getBoolean("ok"))
        r = PartitionManager.resizeDevice(d, 1, 150_000, false)
        assertTrue(r.toString(), r.getBoolean("ok"))
    }
}
