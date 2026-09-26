package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Describes one GPT partition entry to write. */
data class GptPartitionEntry(
    val startLba: Long,
    val sizeInSectors: Long,
    val filesystem: Filesystem,
    val isESP: Boolean,
    val name: String
)

/**
 * Writes a standard GUID Partition Table: a protective MBR (so old tools that
 * only understand MBR leave the disk alone), a primary GPT header + partition
 * array right after it, and a backup copy of both at the very end of the disk -
 * exactly as required by the UEFI spec.
 */
object Gpt {

    private val ESP_TYPE_GUID = UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
    private val BASIC_DATA_TYPE_GUID = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")

    private const val PARTITION_ENTRY_COUNT = 128
    private const val PARTITION_ENTRY_SIZE = 128

    /** Sectors reserved before the first usable LBA: protective MBR + GPT header + entry array. */
    fun reservedSectorsAtStart(blockSize: Int): Long =
        2L + entryArraySectors(blockSize)

    /** Sectors reserved at the end of the disk: backup entry array + backup header. */
    fun reservedSectorsAtEnd(blockSize: Int): Long =
        entryArraySectors(blockSize) + 1L

    private fun entryArraySectors(blockSize: Int): Long =
        ((PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) + blockSize - 1).toLong() / blockSize

    fun write(device: UsbBulkStorageDevice, entries: List<GptPartitionEntry>) {
        require(entries.size <= PARTITION_ENTRY_COUNT) { "Too many partitions for a single GPT entry array" }
        val blockSize = device.blockSize
        val lastLba = device.totalBlocks - 1
        val firstUsableLba = reservedSectorsAtStart(blockSize)
        val lastUsableLba = lastLba - reservedSectorsAtEnd(blockSize)

        val diskGuid = UUID.randomUUID()

        val entryArray = buildEntryArray(entries, blockSize)
        val entryArrayCrc = crc32(entryArray)

        val primaryHeader = buildHeader(
            blockSize, myLba = 1, alternateLba = lastLba,
            firstUsableLba, lastUsableLba, diskGuid,
            partitionEntryLba = 2, entryArrayCrc
        )
        val backupHeader = buildHeader(
            blockSize, myLba = lastLba, alternateLba = 1,
            firstUsableLba, lastUsableLba, diskGuid,
            partitionEntryLba = lastLba - (entryArray.size / blockSize), entryArrayCrc
        )

        // Protective MBR at LBA 0
        device.writeBlocks(0, buildProtectiveMbr(blockSize, device.totalBlocks))

        // Primary: header at LBA1, entry array at LBA2..
        device.writeBlocks(1, primaryHeader)
        device.writeBlocks(2, entryArray)

        // Backup: entry array right before the backup header, header at the very last LBA
        val backupArrayLba = lastLba - (entryArray.size / blockSize)
        device.writeBlocks(backupArrayLba, entryArray)
        device.writeBlocks(lastLba, backupHeader)
    }

    private fun buildProtectiveMbr(blockSize: Int, totalBlocks: Long): ByteArray {
        val sector = ByteArray(blockSize)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(446)
        buf.put(0x00)                                                          // status
        buf.put(byteArrayOf(0x00, 0x02, 0x00))                                 // dummy CHS start
        buf.put(0xEE.toByte())                                                 // type: GPT protective
        buf.put(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))      // dummy CHS end
        buf.putInt(1)                                                          // starting LBA
        val sizeLba = if (totalBlocks - 1 > 0xFFFFFFFFL) 0xFFFFFFFF.toInt() else (totalBlocks - 1).toInt()
        buf.putInt(sizeLba)
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        return sector
    }

    private fun buildHeader(
        blockSize: Int,
        myLba: Long,
        alternateLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partitionEntryLba: Long,
        entryArrayCrc: Long
    ): ByteArray {
        val sector = ByteArray(blockSize)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("EFI PART".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x00010000) // Revision 1.0
        buf.putInt(92)          // HeaderSize
        buf.putInt(0)           // HeaderCRC32 - filled in below, after zeroed here
        buf.putInt(0)           // Reserved
        buf.putLong(myLba)
        buf.putLong(alternateLba)
        buf.putLong(firstUsableLba)
        buf.putLong(lastUsableLba)
        putGuid(buf, diskGuid)
        buf.putLong(partitionEntryLba)
        buf.putInt(PARTITION_ENTRY_COUNT)
        buf.putInt(PARTITION_ENTRY_SIZE)
        buf.putInt(entryArrayCrc.toInt())

        // HeaderCRC32 covers bytes 0-91 with the CRC field itself treated as zero.
        val headerCrc = crc32(sector, 0, 92)
        buf.putInt(16, headerCrc.toInt())

        return sector
    }

    private fun buildEntryArray(entries: List<GptPartitionEntry>, blockSize: Int): ByteArray {
        val arraySectors = (PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) / blockSize
        val array = ByteArray(arraySectors * blockSize)
        val buf = ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN)

        entries.forEach { entry ->
            val typeGuid = when {
                entry.isESP -> ESP_TYPE_GUID
                entry.filesystem == Filesystem.HFSPLUS -> UUID.fromString("48465300-0000-11AA-AA11-00306543ECAC")
                entry.filesystem == Filesystem.LINUX_SWAP -> UUID.fromString("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F")
                LinuxFs.isLinux(entry.filesystem) -> UUID.fromString("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
                else -> BASIC_DATA_TYPE_GUID
            }
            putGuid(buf, typeGuid)
            putGuid(buf, UUID.randomUUID())
            buf.putLong(entry.startLba)
            buf.putLong(entry.startLba + entry.sizeInSectors - 1)
            buf.putLong(0) // Attributes
            val nameBytes = entry.name.take(36).toByteArray(Charsets.UTF_16LE)
            val entryStart = buf.position() - 56
            System.arraycopy(nameBytes, 0, array, entryStart + 56, nameBytes.size)
            buf.position(entryStart + PARTITION_ENTRY_SIZE)
        }
        return array
    }

    /** GUIDs on disk are mixed-endian: first three fields little-endian, last two big-endian. */
    private fun putGuid(buf: ByteBuffer, uuid: UUID) {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val bytes = ByteArray(16)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        bb.putLong(msb)
        bb.putLong(lsb)
        // bytes[0..7] = time_low(4,BE) time_mid(2,BE) time_hi_and_version(2,BE); need LE for first 3 fields
        val out = ByteArray(16)
        out[0] = bytes[3]; out[1] = bytes[2]; out[2] = bytes[1]; out[3] = bytes[0]
        out[4] = bytes[5]; out[5] = bytes[4]
        out[6] = bytes[7]; out[7] = bytes[6]
        // clock_seq + node stay big-endian / byte-for-byte
        System.arraycopy(bytes, 8, out, 8, 8)
        buf.put(out)
    }

    private fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }
}
