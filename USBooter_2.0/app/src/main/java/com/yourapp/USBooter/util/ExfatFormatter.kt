package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a fresh, empty exFAT filesystem directly to a partition, following the
 * on-disk layout in Microsoft's exFAT specification. Implemented in pure Kotlin
 * so it works over raw USB block access without root (no `mkfs.exfat`).
 *
 * The up-case table (used for case-insensitive name comparison) is generated at
 * runtime from Java's Unicode case mapping and compressed per the spec's run-
 * length scheme, rather than embedding Microsoft's default table as a literal
 * byte blob - safer than risking a transcription error in ~6KB of exact binary.
 */
object ExfatFormatter {

    fun format(
        device: UsbBulkStorageDevice,
        partitionStartLba: Long,
        partitionSectorCount: Long,
        volumeLabel: String
    ) {
        val bytesPerSector = device.blockSize
        val bytesPerSectorShift = Integer.numberOfTrailingZeros(bytesPerSector)
        val sectorsPerClusterShift = pickSectorsPerClusterShift(partitionSectorCount, bytesPerSector)
        val sectorsPerCluster = 1 shl sectorsPerClusterShift
        val clusterSizeBytes = sectorsPerCluster * bytesPerSector

        val fatOffset = 24L // right after the 12-sector main + 12-sector backup boot regions
        val clusterCountForFat = ((partitionSectorCount - fatOffset) / (sectorsPerCluster + 4.0 / bytesPerSector)).toLong()
        val fatLength = ceilDiv((clusterCountForFat + 2) * 4, bytesPerSector.toLong())
        val clusterHeapOffset = fatOffset + fatLength
        val clusterCount = (partitionSectorCount - clusterHeapOffset) / sectorsPerCluster

        // ── Lay out cluster heap: bitmap, up-case table, root dir ───────
        val bitmapBytes = ceilDiv(clusterCount, 8L)
        val bitmapClusters = ceilDiv(bitmapBytes, clusterSizeBytes.toLong())

        val upcaseTable = buildCompressedUpcaseTable()
        val upcaseClusters = ceilDiv(upcaseTable.size.toLong(), clusterSizeBytes.toLong())

        val rootDirClusters = 1L

        val bitmapFirstCluster = 2L
        val upcaseFirstCluster = bitmapFirstCluster + bitmapClusters
        val rootDirFirstCluster = upcaseFirstCluster + upcaseClusters
        val usedClusters = bitmapClusters + upcaseClusters + rootDirClusters

        require(rootDirFirstCluster + rootDirClusters <= clusterCount + 2) {
            "Partition too small for exFAT metadata"
        }

        val upcaseChecksum = boot32Checksum(upcaseTable, 0, upcaseTable.size, emptySet())

        // ── Boot sector (+ 8 extended boot sectors) ─────────────────────
        val bootSector = buildBootSector(
            bytesPerSector, bytesPerSectorShift, sectorsPerClusterShift,
            partitionSectorCount, fatOffset, fatLength, clusterHeapOffset,
            clusterCount, rootDirFirstCluster
        )
        val extendedBootSector = buildExtendedBootSector(bytesPerSector)
        val oemParametersSector = ByteArray(bytesPerSector) // no OEM params in use
        val reservedSector = ByteArray(bytesPerSector)

        val mainRegionSectors = mutableListOf<ByteArray>()
        mainRegionSectors.add(bootSector)
        repeat(8) { mainRegionSectors.add(extendedBootSector) }
        mainRegionSectors.add(oemParametersSector)
        mainRegionSectors.add(reservedSector)

        val checksumInput = mainRegionSectors.reduce { acc, s -> acc + s }
        // VolumeFlags (2 bytes at 106) and PercentInUse (1 byte at 112) are excluded
        // from the checksum since they can legitimately change without a rewrite.
        val checksum = boot32Checksum(checksumInput, 0, checksumInput.size, setOf(106, 107, 112))
        val checksumSector = buildChecksumSector(checksum, bytesPerSector)

        // Write main boot region (sectors 0-11) then an identical backup (12-23).
        var lba = partitionStartLba
        for (sector in mainRegionSectors) {
            device.writeBlocks(lba, sector)
            lba++
        }
        device.writeBlocks(lba, checksumSector)
        lba++
        for (sector in mainRegionSectors) {
            device.writeBlocks(lba, sector)
            lba++
        }
        device.writeBlocks(lba, checksumSector)

        // ── FAT region ───────────────────────────────────────────────────
        val fat = ByteArray((fatLength * bytesPerSector).toInt())
        val fatBuf = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        fatBuf.putInt(0xFFFFFFF8.toInt()) // FAT[0]
        fatBuf.putInt(0xFFFFFFFF.toInt()) // FAT[1]
        writeClusterChain(fatBuf, bitmapFirstCluster, bitmapClusters)
        writeClusterChain(fatBuf, upcaseFirstCluster, upcaseClusters)
        writeClusterChain(fatBuf, rootDirFirstCluster, rootDirClusters)
        device.writeBlocks(partitionStartLba + fatOffset, fat)

        // ── Cluster heap: allocation bitmap ────────────────────────────
        val bitmap = ByteArray((bitmapClusters * clusterSizeBytes).toInt())
        markAllocated(bitmap, 0, usedClusters) // clusters are allocated sequentially from index 2
        writeClusterData(device, partitionStartLba, clusterHeapOffset, sectorsPerCluster, bitmapFirstCluster, bitmap)

        // ── Cluster heap: up-case table ─────────────────────────────────
        val upcasePadded = upcaseTable.copyOf((upcaseClusters * clusterSizeBytes).toInt())
        writeClusterData(device, partitionStartLba, clusterHeapOffset, sectorsPerCluster, upcaseFirstCluster, upcasePadded)

        // ── Cluster heap: root directory ─────────────────────────────────
        val rootDir = buildRootDirectory(
            clusterSizeBytes, volumeLabel,
            bitmapFirstCluster, bitmapBytes,
            upcaseFirstCluster, upcaseTable.size.toLong(), upcaseChecksum
        )
        writeClusterData(device, partitionStartLba, clusterHeapOffset, sectorsPerCluster, rootDirFirstCluster, rootDir)
    }

    // ── Layout helpers ───────────────────────────────────────────────────

    private fun pickSectorsPerClusterShift(totalSectors: Long, bytesPerSector: Int): Int {
        val sizeInMB = (totalSectors * bytesPerSector) / (1024 * 1024)
        val targetClusterBytes = when {
            sizeInMB < 256 -> 4 * 1024
            sizeInMB < 32_768 -> 32 * 1024
            else -> 128 * 1024
        }
        var shift = 0
        while ((1 shl shift) * bytesPerSector < targetClusterBytes) shift++
        return shift
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    private fun writeClusterChain(fatBuf: ByteBuffer, firstCluster: Long, clusterCount: Long) {
        fatBuf.position((firstCluster * 4).toInt())
        for (i in 0 until clusterCount) {
            val isLast = i == clusterCount - 1
            val value = if (isLast) 0xFFFFFFFF.toInt() else (firstCluster + i + 1).toInt()
            fatBuf.putInt(value)
        }
    }

    private fun markAllocated(bitmap: ByteArray, startBit: Long, count: Long) {
        for (i in 0 until count) {
            val bit = startBit + i
            val byteIndex = (bit / 8).toInt()
            val bitIndex = (bit % 8).toInt()
            bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
    }

    /** Writes [data] (already padded to a whole number of clusters) starting at cluster [firstCluster]. */
    private fun writeClusterData(
        device: UsbBulkStorageDevice,
        partitionStartLba: Long,
        clusterHeapOffset: Long,
        sectorsPerCluster: Int,
        firstCluster: Long,
        data: ByteArray
    ) {
        val startSector = clusterHeapOffset + (firstCluster - 2) * sectorsPerCluster
        device.writeBlocks(partitionStartLba + startSector, data)
    }

    // ── Sector builders ─────────────────────────────────────────────────

    private fun buildBootSector(
        bytesPerSector: Int,
        bytesPerSectorShift: Int,
        sectorsPerClusterShift: Int,
        volumeLength: Long,
        fatOffset: Long,
        fatLength: Long,
        clusterHeapOffset: Long,
        clusterCount: Long,
        rootDirFirstCluster: Long
    ): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(byteArrayOf(0xEB.toByte(), 0x76, 0x90.toByte())) // JumpBoot
        buf.put("EXFAT   ".toByteArray(Charsets.US_ASCII))       // FileSystemName

        buf.position(64)
        buf.putLong(0) // PartitionOffset - unknown/unused
        buf.putLong(volumeLength)
        buf.putInt(fatOffset.toInt())
        buf.putInt(fatLength.toInt())
        buf.putInt(clusterHeapOffset.toInt())
        buf.putInt(clusterCount.toInt())
        buf.putInt(rootDirFirstCluster.toInt())
        buf.putInt((System.currentTimeMillis() and 0xFFFFFFFFL).toInt()) // VolumeSerialNumber
        buf.putShort(0x0100) // FileSystemRevision 1.00
        buf.putShort(0)      // VolumeFlags
        buf.put(bytesPerSectorShift.toByte())
        buf.put(sectorsPerClusterShift.toByte())
        buf.put(1)            // NumberOfFats
        buf.put(0x80.toByte()) // DriveSelect
        buf.put(0)             // PercentInUse - unknown

        sector[bytesPerSector - 2] = 0x55
        sector[bytesPerSector - 1] = 0xAA.toByte()
        return sector
    }

    private fun buildExtendedBootSector(bytesPerSector: Int): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(bytesPerSector - 4)
        buf.putInt(0xAA550000.toInt()) // ExtendedBootSignature
        return sector
    }

    private fun buildChecksumSector(checksum: Int, bytesPerSector: Int): ByteArray {
        val sector = ByteArray(bytesPerSector)
        val buf = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        repeat(bytesPerSector / 4) { buf.putInt(checksum) }
        return sector
    }

    /** The exFAT boot-region checksum: a 32-bit rotate-and-add over the given bytes. */
    private fun boot32Checksum(data: ByteArray, offset: Int, length: Int, excludedOffsets: Set<Int>): Int {
        var checksum = 0
        for (i in 0 until length) {
            if ((offset + i) in excludedOffsets) continue
            val byte = data[offset + i].toInt() and 0xFF
            checksum = (if (checksum and 1 != 0) 0x80000000.toInt() else 0) +
                (checksum ushr 1) + byte
        }
        return checksum
    }

    /**
     * Builds the up-case table using Java's Unicode uppercase mapping over the
     * Basic Multilingual Plane, compressed per the exFAT spec: runs of characters
     * that map to themselves are replaced with a (0xFFFF, runLength) marker pair.
     */
    private fun buildCompressedUpcaseTable(): ByteArray {
        val table = ShortArray(0x10000)
        for (i in 0 until 0x10000) {
            val upper = Character.toUpperCase(i)
            table[i] = if (upper in 0..0xFFFF) upper.toShort() else i.toShort()
        }

        val out = java.io.ByteArrayOutputStream()
        val entry = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < 0x10000) {
            if (table[i] == i.toShort()) {
                var runLen = 0
                while (i + runLen < 0x10000 && table[i + runLen] == (i + runLen).toShort() && runLen < 0xFFFE) {
                    runLen++
                }
                entry.clear(); entry.putShort(0xFFFF.toShort()); out.write(entry.array())
                entry.clear(); entry.putShort(runLen.toShort()); out.write(entry.array())
                i += runLen
            } else {
                entry.clear(); entry.putShort(table[i]); out.write(entry.array())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun buildRootDirectory(
        clusterSizeBytes: Int,
        volumeLabel: String,
        bitmapFirstCluster: Long,
        bitmapDataLength: Long,
        upcaseFirstCluster: Long,
        upcaseDataLength: Long,
        upcaseChecksum: Int
    ): ByteArray {
        val dir = ByteArray(clusterSizeBytes)
        var offset = 0

        // Volume Label entry (0x83 = in-use, 0x03 = same type but "unused" bit set;
        // we always write a label entry, using an empty label if none was given).
        val label = volumeLabel.take(11)
        dir[offset] = 0x83.toByte()
        dir[offset + 1] = label.length.toByte()
        val labelBytes = label.toByteArray(Charsets.UTF_16LE)
        System.arraycopy(labelBytes, 0, dir, offset + 2, labelBytes.size)
        offset += 32

        // Allocation Bitmap entry (0x81)
        dir[offset] = 0x81.toByte()
        dir[offset + 1] = 0 // BitmapFlags: this is the 1st (only) FAT's bitmap
        ByteBuffer.wrap(dir, offset + 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(bitmapFirstCluster.toInt())
        ByteBuffer.wrap(dir, offset + 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(bitmapDataLength)
        offset += 32

        // Up-case Table entry (0x82)
        dir[offset] = 0x82.toByte()
        ByteBuffer.wrap(dir, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(upcaseChecksum)
        ByteBuffer.wrap(dir, offset + 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(upcaseFirstCluster.toInt())
        ByteBuffer.wrap(dir, offset + 24, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(upcaseDataLength)
        offset += 32

        // Remaining entries in the cluster are left zeroed (EntryType 0x00 = end of directory).
        return dir
    }
}
