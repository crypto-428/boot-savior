package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Describes one primary partition entry to write into the MBR. */
data class MbrPartitionEntry(
    val startLba: Long,
    val sizeInSectors: Long,
    val filesystem: Filesystem,
    val isESP: Boolean,
    /** Sets the 0x80 "active" flag - a legacy BIOS only boots an active partition. */
    val bootable: Boolean = false,
    /**
     * Overrides the partition type byte. Used for filesystems the app can write
     * but that are not in [Filesystem], e.g. 0x83 for the ext2 persistence
     * partition, so Linux recognises it as a Linux filesystem.
     */
    val typeOverride: Byte? = null
)

/**
 * Writes a Master Boot Record with up to four primary partitions.
 *
 * Three things here are what actually decide whether a legacy BIOS will boot the
 * stick, and all three were missing before:
 *  - real CHS values (old BIOSes and some UEFI CSMs reject 0xFEFFFF everywhere),
 *  - the 0x80 active flag on the boot partition,
 *  - 440 bytes of bootstrap code that chainloads that active partition.
 */
object Mbr {

    /** Classic BIOS translation geometry used by every partitioning tool. */
    private const val HEADS = 255
    private const val SECTORS_PER_TRACK = 63

    fun write(
        device: UsbBulkStorageDevice,
        entries: List<MbrPartitionEntry>,
        /** Install the chainloader bootstrap (needed for legacy BIOS boot). */
        installBootCode: Boolean = true,
        diskSignature: Int = Random.nextInt()
    ) {
        require(entries.size in 1..4) { "MBR supports 1-4 primary partitions" }

        val sector = ByteArray(device.blockSize)
        if (installBootCode) {
            System.arraycopy(MbrBootCode.CODE, 0, sector, 0, MbrBootCode.CODE.size)
        }

        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(440)
        buf.putInt(diskSignature) // disk signature - Windows/Linux both expect a non-zero one
        buf.putShort(0)           // reserved

        // If the caller marked nothing bootable, make the first entry active so the
        // bootstrap has something to chainload.
        val activeIndex = entries.indexOfFirst { it.bootable }.let { if (it >= 0) it else 0 }

        entries.forEachIndexed { index, entry ->
            buf.position(446 + index * 16)
            buf.put(if (index == activeIndex) 0x80.toByte() else 0x00)
            buf.put(chs(entry.startLba))
            buf.put(partitionTypeId(entry))
            buf.put(chs(entry.startLba + entry.sizeInSectors - 1))
            buf.putInt(entry.startLba.toInt())
            buf.putInt(entry.sizeInSectors.toInt())
        }

        sector[510] = 0x55
        sector[511] = 0xAA.toByte()

        device.writeBlocks(0, sector)
    }

    /** LBA -> 3-byte CHS, saturating at the classic 1023/254/63 maximum. */
    fun chs(lba: Long): ByteArray {
        val maxLba = 1024L * HEADS * SECTORS_PER_TRACK - 1
        if (lba > maxLba) return byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val cylinder = (lba / (HEADS * SECTORS_PER_TRACK)).toInt()
        val temp = (lba % (HEADS * SECTORS_PER_TRACK)).toInt()
        val head = temp / SECTORS_PER_TRACK
        val sector = temp % SECTORS_PER_TRACK + 1
        return byteArrayOf(
            head.toByte(),
            (sector or (((cylinder shr 8) and 0x03) shl 6)).toByte(),
            (cylinder and 0xFF).toByte()
        )
    }

    fun partitionTypeId(entry: MbrPartitionEntry): Byte {
        entry.typeOverride?.let { return it }
        if (entry.isESP) return 0xEF.toByte() // EFI System Partition
        return when (entry.filesystem) {
            Filesystem.FAT32 -> 0x0C.toByte() // FAT32, LBA
            Filesystem.EXFAT -> 0x07.toByte() // exFAT (shares the NTFS/exFAT type id)
            Filesystem.NTFS -> 0x07.toByte() // NTFS (shares the NTFS/exFAT type id)
        }
    }
}
