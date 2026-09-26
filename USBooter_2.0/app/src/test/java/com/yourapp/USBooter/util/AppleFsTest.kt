package com.yourapp.USBooter.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.RandomAccessFile

/**
 * Builds APFS containers of several sizes and an HFS+ volume and saves them to
 * /tmp so tools/run-repair-tests.sh can run the real apfsck / fsck.hfsplus on them.
 */
class AppleFsTest {
    private fun apfs(blocks: Long) {
        val img = ApfsTemplate.build(blocks, "MyMac")
        assertEquals("NXSB", String(img, 32, 4, Charsets.US_ASCII))
        RandomAccessFile("/tmp/applefs-apfs-$blocks.img", "rw").use {
            it.setLength(0); it.write(img); it.setLength(blocks * 4096)
        }
    }
    @Test fun apfsTiny() = apfs(512)
    @Test fun apfsTemplateSize() = apfs(24410)
    @Test fun apfsOneGb() = apfs(262144)
    @Test fun apfsTwentyGb() = apfs(5_000_000)
    @Test fun apfsLarge() = apfs(60_000_000)

    @Test fun hfsPlus() {
        val d = FakeBlockDevice(131072)
        HfsPlusFormatter.format(d, 0, 131072, "HFS_TEST")
        assertEquals("HFS+", HfsPlusFormatter.detect(d, 0))
        val out = java.io.File("/tmp/applefs-hfs.img").outputStream().buffered()
        for (i in 0 until d.totalBlocks) out.write(d.sector(i))
        out.close()
    }
}
