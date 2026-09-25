package com.yourapp.USBooter.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxFsTest {
    private fun dump(d: FakeBlockDevice, name: String) {
        val out = java.io.File("/tmp/linuxfs-$name.img").outputStream().buffered()
        for (i in 0 until d.totalBlocks) out.write(d.sector(i))
        out.close()
    }
    @Test fun ext4() { val d = FakeBlockDevice(131072); LinuxFs.format(d, 0, 131072, "EXT4_TEST", Filesystem.EXT4); assertEquals("ext4", LinuxFs.detect(d, 0)); dump(d, "ext4") }
    @Test fun ext2() { val d = FakeBlockDevice(131072); LinuxFs.format(d, 0, 131072, "EXT2", Filesystem.EXT2); assertEquals("ext2", LinuxFs.detect(d, 0)); dump(d, "ext2") }
    @Test fun swap() { val d = FakeBlockDevice(65536); LinuxFs.format(d, 0, 65536, "", Filesystem.LINUX_SWAP); assertEquals("swap", LinuxFs.detect(d, 0)) }
}
