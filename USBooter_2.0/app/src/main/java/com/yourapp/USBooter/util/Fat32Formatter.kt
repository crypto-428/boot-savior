package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a fresh, empty FAT32 filesystem directly to a partition, following the
 * on-disk layout described in Microsoft's FAT32 specification (fatgen103). This
 * mirrors what `mkfs.fat -F32` does, implemented in pure Kotlin so it works over
 * raw USB block access without root.
 *
 * [partitionStartLba] / [partitionSectorCount] describe the target partition in
 * sectors, relative to the start of the disk.
 */
object Fat32Formatter {

    fun format(
        device: UsbBulkStorageDevice,
        partitionStartLba: Long,
        partitionSectorCount: Long,
        volumeLabel: String
    ) {
        val bytesPerSector = device.blockSize
        val sectorsPerCluster = pickSectorsPerCluster(partitionSectorCount, bytesPerSector)
        val reservedSectorCount = 32
        val numFats = 2

        val fatSz32 = computeFatSize(
            totalSectors = partitionSectorCount,
            reservedSectorCount = reservedSectorCount,
            numFats = numFats,
            sectorsPerCluster = sectorsPerCluster,
            bytesPerSector = bytesPerSector
        )

        val fatRegionStart = partitionStartLba + reservedSectorCount
        val dataRegionStart = fatRegionStart + numFats.toLong() * fatSz32
        val rootDirCluster = 2L

        // ── Boot sector + backup (BkBootSec = 6) ──────────────────────
        val bootSector = buildBootSector(
            bytesPerSector, sectorsPerCluster, reservedSectorCount, numFats,
            partitionSectorCount, fatSz32, rootDirCluster, volumeLabel
        )
        device.writeBlocks(partitionStartLba, bootSector)
        device.writeBlocks(partitionStartLba + 6, bootSector)

        // ── FSInfo sector + backup ─────────────────────────────────────
        val dataClusterCount = (partitionSectorCount - reservedSectorCount - numFats.toLong() * fatSz32) / sectorsPerCluster
        val freeClusterCount = (dataClusterCount - 1).coerceAtLeast(0) // minus the root dir's cluster
        val fsInfo = buildFsInfoSector(freeClusterCount, nextFree = 3, bytesPerSector)
        device.writeBlocks(partitionStartLba + 1, fsInfo)
        device.writeBlocks(partitionStartLba + 7, fsInfo)

        // Reserved region sectors 2-5 and 8-31 are left zeroed (already blank on a
        // freshly zeroed partition); we don't need to explicitly rewrite them.

        // ── FAT tables (both copies identical) ─────────────────────────
        val fat = buildInitialFat(fatSz32, bytesPerSector)
        device.writeBlocks(fatRegionStart, fat)
        device.writeBlocks(fatRegionStart + fatSz32, fat)

        // ── Root directory: one cluster holding just the volume label ──
        // Windows, Linux and Android all read the *label entry in the root
        // directory* - not the BPB field - to name a FAT32 volume. Without it the
        // stick showed up as "USB drive" / unnamed after formatting.
        val rootDirSectors = sectorsPerCluster
        device.writeZeroBlocks(dataRegionStart, rootDirSectors)
        device.writeBlocks(dataRegionStart, buildVolumeLabelSector(volumeLabel, bytesPerSector))
    }

    /** A single 32-byte ATTR_VOLUME_ID entry (0x08) at the start of the root cluster. */
    private fun buildVolumeLabelSector(volumeLabel: String, bytesPerSector: Int): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val label = sanitizeVolumeLabel(volumeLabel)
        System.arraycopy(label.toByteArray(Charsets.US_ASCII), 0, sector, 0, 11)
        sector[11] = 0x08 // ATTR_VOLUME_ID
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(22, fatTime())
        buf.putShort(24, fatDate())
        return sector
    }

    private fun fatTime(): Short {
        val cal = java.util.Calendar.getInstance()
        val seconds = cal.get(java.util.Calendar.SECOND) / 2
        return (((cal.get(java.util.Calendar.HOUR_OF_DAY) and 0x1F) shl 11) or
            ((cal.get(java.util.Calendar.MINUTE) and 0x3F) shl 5) or (seconds and 0x1F)).toShort()
    }

    private fun fatDate(): Short {
        val cal = java.util.Calendar.getInstance()
        val year = (cal.get(java.util.Calendar.YEAR) - 1980).coerceIn(0, 127)
        return (((year and 0x7F) shl 9) or
            (((cal.get(java.util.Calendar.MONTH) + 1) and 0x0F) shl 5) or
            (cal.get(java.util.Calendar.DAY_OF_MONTH) and 0x1F)).toShort()
    }


    // ── Layout math (Microsoft fatgen103 formulas) ─────────────────────

    private fun pickSectorsPerCluster(totalSectors: Long, bytesPerSector: Int): Int {
        val sizeInMB = (totalSectors * bytesPerSector) / (1024 * 1024)
        // Roughly matches what Windows' format.exe picks for FAT32.
        return when {
            sizeInMB < 8_192 -> 8    // < 8 GB  -> 4 KB clusters
            sizeInMB < 16_384 -> 16  // < 16 GB -> 8 KB clusters
            sizeInMB < 32_768 -> 32  // < 32 GB -> 16 KB clusters
            else -> 64               // >= 32 GB -> 32 KB clusters
        }
    }

    private fun computeFatSize(
        totalSectors: Long,
        reservedSectorCount: Int,
        numFats: Int,
        sectorsPerCluster: Int,
        bytesPerSector: Int
    ): Long {
        val tmpVal1 = totalSectors - reservedSectorCount
        var tmpVal2 = (256L * sectorsPerCluster) + numFats
        tmpVal2 /= 2
        return (tmpVal1 + (tmpVal2 - 1)) / tmpVal2
    }

    // ── Sector builders ─────────────────────────────────────────────────

    private fun buildBootSector(
        bytesPerSector: Int,
        sectorsPerCluster: Int,
        reservedSectorCount: Int,
        numFats: Int,
        totalSectors: Long,
        fatSz32: Long,
        rootCluster: Long,
        volumeLabel: String
    ): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)

        // Jump instruction + OEM name
        buf.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()))
        buf.put("USBOOTER".toByteArray(Charsets.US_ASCII)) // 8-byte OEM name

        buf.position(11)
        buf.putShort(bytesPerSector.toShort())
        buf.put(sectorsPerCluster.toByte())
        buf.putShort(reservedSectorCount.toShort())
        buf.put(numFats.toByte())
        buf.putShort(0) // RootEntCnt = 0 for FAT32
        buf.putShort(0) // TotSec16 = 0, using TotSec32 instead
        buf.put(0xF8.toByte()) // Media descriptor: fixed disk
        buf.putShort(0) // FATSz16 = 0 for FAT32
        buf.putShort(0x3F) // SecPerTrk (conventional value)
        buf.putShort(0xFF) // NumHeads (conventional value)
        buf.putInt(0) // HiddSec - offset from start of physical disk; left 0 since
                       // this formatter addresses sectors relative to the partition
        buf.putInt(totalSectors.toInt())
        buf.putInt(fatSz32.toInt())
        buf.putShort(0) // ExtFlags
        buf.putShort(0) // FSVer 0.0
        buf.putInt(rootCluster.toInt())
        buf.putShort(1) // FSInfo sector number
        buf.putShort(6) // Backup boot sector number
        buf.position(buf.position() + 12) // Reserved
        buf.put(0x80.toByte()) // DrvNum
        buf.put(0) // Reserved1
        buf.put(0x29) // BootSig - indicates VolID/VolLab/FilSysType are present
        buf.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt()) // VolID

        val label = sanitizeVolumeLabel(volumeLabel)
        buf.put(label.toByteArray(Charsets.US_ASCII))
        buf.put("FAT32   ".toByteArray(Charsets.US_ASCII))

        // Boot signature at the very end of the sector
        sector[bytesPerSector - 2] = 0x55
        sector[bytesPerSector - 1] = 0xAA.toByte()

        return sector
    }

    private fun buildFsInfoSector(freeClusterCount: Long, nextFree: Long, bytesPerSector: Int): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x41615252) // LeadSig
        buf.position(484)
        buf.putInt(0x61417272) // StrucSig
        buf.putInt(freeClusterCount.toInt()) // Free_Count
        buf.putInt(nextFree.toInt()) // Nxt_Free
        buf.position(508)
        buf.putInt(0xAA550000.toInt()) // TrailSig (0x00,0x00,0x55,0xAA in file order)
        return sector
    }

    private fun buildInitialFat(fatSz32: Long, bytesPerSector: Int): ByteArray {
        val fat = ByteArray((fatSz32 * bytesPerSector).toInt())
        val buf = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x0FFFFFF8.toInt()) // FAT[0]: media descriptor
        buf.putInt(0x0FFFFFFF.toInt()) // FAT[1]: reserved, EOC-style
        buf.putInt(0x0FFFFFFF.toInt()) // FAT[2]: root dir cluster, end of its (1-cluster) chain
        return fat
    }

    private fun sanitizeVolumeLabel(label: String): String {
        val cleaned = label.uppercase()
            .map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
            .joinToString("")
        return cleaned.padEnd(11, ' ').take(11)
    }
}
