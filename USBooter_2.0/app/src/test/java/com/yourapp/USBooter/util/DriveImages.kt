package com.yourapp.USBooter.util

import java.util.zip.CRC32

/**
 * Synthetic drive images for the repair tests: MBR/GPT tables and NTFS boot
 * sectors built byte by byte, so each test can damage exactly one structure and
 * assert what the engine decides to do about it.
 */
object DriveImages {

    const val PART_START = 2048L
    const val PART_SECTORS = 16000L

    fun le(b: ByteArray, off: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[off + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    /** MBR with one primary partition, optionally active, of the given type byte. */
    fun mbr(
        start: Long = PART_START,
        sectors: Long = PART_SECTORS,
        type: Int = 0x07,
        active: Boolean = true
    ): ByteArray {
        val s = ByteArray(512)
        val base = 446
        if (active) s[base] = 0x80.toByte()
        s[base + 4] = type.toByte()
        le(s, base + 8, start, 4)
        le(s, base + 12, sectors, 4)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        return s
    }

    /** Protective MBR of a GPT drive. */
    fun protectiveMbr(lastLba: Long): ByteArray {
        val s = ByteArray(512)
        val base = 446
        s[base + 4] = 0xEE.toByte()
        le(s, base + 8, 1L, 4)
        le(s, base + 12, minOf(lastLba, 0xFFFFFFFFL), 4)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        return s
    }

    /** A GPT header with a correct CRC. [entryCount] 0 keeps the drive partition-free. */
    fun gptHeader(myLba: Long, altLba: Long, entryLba: Long, totalBlocks: Long, entryCount: Long = 0): ByteArray {
        val h = ByteArray(512)
        System.arraycopy("EFI PART".toByteArray(Charsets.US_ASCII), 0, h, 0, 8)
        le(h, 8, 0x00010000L, 4)   // revision 1.0
        le(h, 12, 92L, 4)          // header size
        le(h, 24, myLba, 8)
        le(h, 32, altLba, 8)
        le(h, 40, 34L, 8)
        le(h, 48, totalBlocks - 34, 8)
        le(h, 72, entryLba, 8)
        le(h, 80, entryCount, 4)
        le(h, 84, 128L, 4)
        val crc = CRC32()
        crc.update(h, 0, 92)
        le(h, 16, crc.value, 4)
        return h
    }

    /**
     * A healthy NTFS boot sector. [totalSectors] is what NTFS records, which is
     * one less than the partition; [mftCluster] points at the file table.
     */
    fun ntfsBoot(
        totalSectors: Long = PART_SECTORS - 1,
        sectorsPerCluster: Int = 8,
        mftCluster: Long = 100,
        mirrorCluster: Long = 200,
        bytesPerSector: Int = 512
    ): ByteArray {
        val s = ByteArray(512)
        s[0] = 0xEB.toByte(); s[1] = 0x52; s[2] = 0x90.toByte()
        System.arraycopy("NTFS    ".toByteArray(Charsets.US_ASCII), 0, s, 3, 8)
        le(s, 11, bytesPerSector.toLong(), 2)
        s[13] = sectorsPerCluster.toByte()
        s[21] = 0xF8.toByte()
        le(s, 40, totalSectors, 8)
        le(s, 48, mftCluster, 8)
        le(s, 56, mirrorCluster, 8)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        return s
    }

    /**
     * A plausible MFT record: the engine checks the update-sequence array and the
     * record lengths, not just the "FILE" magic, so the fixture supplies them.
     */
    fun mftRecord(): ByteArray {
        val s = ByteArray(512)
        System.arraycopy("FILE".toByteArray(Charsets.US_ASCII), 0, s, 0, 4)
        le(s, 4, 48L, 2)    // update-sequence array offset
        le(s, 6, 3L, 2)     // update-sequence array entries
        le(s, 20, 56L, 2)   // first attribute offset, past the sequence array
        le(s, 24, 400L, 4)  // bytes used
        le(s, 28, 1024L, 4) // bytes allocated
        return s
    }


    /** MBR drive holding one intact NTFS partition, boot-sector copy and MFT included. */
    fun healthyNtfsDrive(): FakeBlockDevice {
        val device = FakeBlockDevice(totalBlocks = 20_000)
        device.put(0, mbr())
        val boot = ntfsBoot()
        device.put(PART_START, boot)
        device.put(PART_START + PART_SECTORS - 1, boot)
        device.put(PART_START + 100 * 8, mftRecord())
        device.put(PART_START + 200 * 8, mftRecord())
        return device
    }

    /** Big enough for a real NTFS rebuild (the formatter needs at least 16 MiB). */
    const val BIG_PART_SECTORS = 120_000L
    const val BIG_TOTAL_BLOCKS = 130_000L

    /**
     * One listed NTFS partition of a realistic size whose boot sector, backup
     * copy and file tables are all destroyed: nothing can be recovered from the
     * drive itself, so only an in-place rebuild is left.
     */
    fun hopelessNtfsPartition(): FakeBlockDevice {
        val device = FakeBlockDevice(BIG_TOTAL_BLOCKS)
        device.put(0, mbr(sectors = BIG_PART_SECTORS))
        val broken = ntfsBoot(totalSectors = 9_999_999L, mftCluster = 9_999_999L, mirrorCluster = 9_999_999L)
        device.put(PART_START, broken)
        device.put(PART_START + BIG_PART_SECTORS - 1, broken)
        return device
    }
}

/**
 * Extra fixtures for the surface-sweep tests: a FAT32 boot sector that no other
 * structure on the drive confirms (a leftover from an old format), and a real
 * unlisted NTFS volume that does have its backup copy in place.
 */
object SurfaceImages {

    fun fat32Boot(totalSectors: Long = 4000): ByteArray {
        val s = ByteArray(512)
        DriveImages.le(s, 11, 512L, 2)
        s[13] = 8
        DriveImages.le(s, 32, totalSectors, 4)
        System.arraycopy("FAT32   ".toByteArray(Charsets.US_ASCII), 0, s, 82, 8)
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        return s
    }

    /** Healthy listed NTFS partition plus a stale FAT32 signature further along. */
    fun driveWithStaleFat32Signature(): FakeBlockDevice {
        val device = DriveImages.healthyNtfsDrive()
        device.put(19_000, fat32Boot())
        return device
    }

    /** Healthy listed NTFS partition plus a genuine unlisted NTFS volume. */
    fun driveWithUnlistedNtfsVolume(): FakeBlockDevice {
        val device = FakeBlockDevice(totalBlocks = 60_000)
        device.put(0, DriveImages.mbr())
        val boot = DriveImages.ntfsBoot()
        device.put(DriveImages.PART_START, boot)
        device.put(DriveImages.PART_START + DriveImages.PART_SECTORS - 1, boot)
        device.put(DriveImages.PART_START + 100 * 8, DriveImages.mftRecord())
        device.put(DriveImages.PART_START + 200 * 8, DriveImages.mftRecord())
        val orphan = DriveImages.ntfsBoot(totalSectors = 3999)
        device.put(20_000, orphan)
        device.put(20_000 + 3999, orphan)
        return device
    }
}
