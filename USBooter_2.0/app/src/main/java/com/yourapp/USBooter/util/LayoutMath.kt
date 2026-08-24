package com.yourapp.USBooter.util

/**
 * All LBA / sector arithmetic used by the flashing modes, kept free of any
 * device or Android dependency so it can be unit-tested exactly as the engine
 * runs it. [FormatEngine] must not duplicate any of this maths.
 */
object LayoutMath {

    /** 1 MiB at 512 B/sector: the alignment every partition start is snapped to. */
    const val ALIGNMENT_SECTORS = 2048L

    fun alignUp(sectors: Long): Long =
        ((sectors + ALIGNMENT_SECTORS - 1) / ALIGNMENT_SECTORS) * ALIGNMENT_SECTORS

    fun alignDown(sectors: Long): Long = (sectors / ALIGNMENT_SECTORS) * ALIGNMENT_SECTORS

    fun megabytesToSectors(mb: Int, blockSize: Int): Long = (mb.toLong() * 1_000_000L) / blockSize

    /** The layout UNIVERSAL / WINDOWS mode writes, in absolute LBAs. */
    data class UniversalLayout(
        val bootStart: Long,
        val bootSectors: Long,
        val persistenceStart: Long,
        val persistenceSectors: Long,
        val dataStart: Long,
        val dataSectors: Long,
        /** Non-null when the request cannot be satisfied on this drive. */
        val problem: String? = null
    ) {
        val hasPersistence: Boolean get() = persistenceSectors > 0
        val hasData: Boolean get() = dataSectors > 0
        val fits: Boolean get() = problem == null
        /** Last sector the layout occupies; must stay inside the drive. */
        val endLba: Long get() = maxOf(bootStart + bootSectors, persistenceStart + persistenceSectors, dataStart + dataSectors)
    }

    /**
     * Computes the FAT32 boot partition (+ optional ext2 persistence and a
     * trailing data partition) for UNIVERSAL / WINDOWS mode.
     *
     * @param payloadBytes total bytes of the image that has to be written.
     * @param oversizedBytes size of the single file that cannot live on FAT32
     *        (Windows install.wim/esd), 0 when there is none.
     * @param split true when that oversized file goes to a second exFAT partition.
     */
    fun planUniversal(
        totalBlocks: Long,
        blockSize: Int,
        payloadBytes: Long,
        oversizedBytes: Long = 0L,
        split: Boolean = false,
        persistenceMB: Int = 0,
        useRemainingSpace: Boolean = false,
        bootPartitionMBOverride: Int = 0
    ): UniversalLayout {
        val bootStart = ALIGNMENT_SECTORS
        val available = totalBlocks - bootStart - ALIGNMENT_SECTORS
        if (available <= 0) {
            return UniversalLayout(bootStart, 0, 0, 0, 0, 0, "The drive is too small for a bootable layout")
        }

        // 15 % headroom for FAT overhead, never below 512 MB.
        val wantedBytes = (payloadBytes * 115) / 100 + 256L * 1024 * 1024
        val bootSectorsWanted = (wantedBytes / blockSize).coerceAtLeast(512L * 1024 * 1024 / blockSize)
        val smallPayloadBytes = ((payloadBytes - oversizedBytes).coerceAtLeast(0) * 115) / 100 + 512L * 1024 * 1024

        val persistenceSectors =
            if (persistenceMB > 0) alignDown(persistenceMB.toLong() * 1_000_000 / blockSize) else 0L

        val wantsData = split ||
            (useRemainingSpace && (available - bootSectorsWanted - persistenceSectors) > (1024L * 1024 * 1024 / blockSize))

        val overrideBootSectors =
            if (bootPartitionMBOverride > 0) alignDown(bootPartitionMBOverride.toLong() * 1_000_000 / blockSize) else 0L
        val bootSectors = when {
            overrideBootSectors > 0 -> overrideBootSectors
            split -> alignDown((smallPayloadBytes / blockSize).coerceAtLeast(1024L * 1024 * 1024 / blockSize))
            wantsData -> alignDown(bootSectorsWanted)
            else -> alignDown(available - persistenceSectors)
        }

        val persistenceStart = alignUp(bootStart + bootSectors)
        val dataStart = alignUp(persistenceStart + persistenceSectors)
        val dataSectors = if (wantsData) alignDown(totalBlocks - ALIGNMENT_SECTORS - dataStart) else 0L

        val problem = when {
            bootSectors <= 0 -> "The boot partition would be empty"
            bootStart + bootSectors + persistenceSectors > totalBlocks - ALIGNMENT_SECTORS ->
                "Boot partition ${bootSectors * blockSize / 1_000_000} MB + persistence " +
                    "${persistenceSectors * blockSize / 1_000_000} MB exceed ${totalBlocks * blockSize / 1_000_000} MB"
            wantsData && dataSectors <= 0 -> "There is no room left for the data partition"
            else -> null
        }
        return UniversalLayout(
            bootStart, bootSectors, persistenceStart, persistenceSectors, dataStart, dataSectors, problem
        )
    }

    /** How a raw clone maps onto the device: it always starts at LBA 0. */
    data class RawCloneLayout(val blocks: Long, val lastLba: Long, val unusedBlocks: Long, val fits: Boolean)

    fun planRawClone(imageBytes: Long, totalBlocks: Long, blockSize: Int): RawCloneLayout {
        val blocks = (imageBytes + blockSize - 1) / blockSize
        return RawCloneLayout(
            blocks = blocks,
            lastLba = (blocks - 1).coerceAtLeast(0),
            unusedBlocks = (totalBlocks - blocks).coerceAtLeast(0),
            fits = blocks <= totalBlocks
        )
    }

    /** Sequential layout used by the plain (non-bootable) partition editor. */
    data class PlannedRange(val startLba: Long, val sizeInSectors: Long)

    fun planSequential(
        totalSectors: Long,
        blockSize: Int,
        sizesMB: List<Int>,
        startCursor: Long,
        trailingReserved: Long
    ): List<PlannedRange> {
        var cursor = startCursor
        val fixed = sizesMB.filter { it > 0 }.sumOf { megabytesToSectors(it, blockSize) }
        val remaining = (totalSectors - cursor - fixed - trailingReserved).coerceAtLeast(0)
        return sizesMB.map { mb ->
            val size = if (mb == -1) remaining else megabytesToSectors(mb, blockSize)
            val range = PlannedRange(cursor, size)
            cursor += size
            range
        }
    }
}
