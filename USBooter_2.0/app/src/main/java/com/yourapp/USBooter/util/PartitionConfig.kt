package com.yourapp.USBooter.util

import java.io.Serializable

/**
 * Filesystems this app can create without root, by writing the on-disk format
 * directly over raw USB block access. NTFS/EXT4/FAT16 formatting always required
 * root-only tools (mkfs.ntfs/mkfs.ext4) with no practical non-root equivalent, so
 * they're not offered here.
 */
enum class Filesystem(val displayName: String) : Serializable {
    FAT32("FAT32"),
    EXFAT("exFAT"),
    NTFS("NTFS")
}

enum class PartitionTableType(val displayName: String) : Serializable {
    MBR("MBR"),
    GPT("GPT")
}

/**
 * Defines a single partition in the layout.
 */
data class PartitionDefinition(
    val id: Int,
    val label: String,
    val sizeMB: Int,          // -1 means "fill remaining space"
    val filesystem: Filesystem,
    val isESP: Boolean = false,
    /** Exact repaired geometry. Both values must be supplied together. */
    val startLba: Long? = null,
    val sizeSectors: Long? = null
) : Serializable

/**
 * Complete partition layout configuration.
 */
data class LayoutConfig(
    val partitions: List<PartitionDefinition>,
    val tableType: PartitionTableType = PartitionTableType.MBR,
    /** Deep format: overwrite every sector with zeros before partitioning (slow, any filesystem). */
    val deepFormat: Boolean = false
) : Serializable {

    /**
     * Returns the total fixed size (partitions with sizeMB > 0).
     */
    fun totalFixedSizeMB(): Int {
        return partitions.filter { it.sizeMB > 0 }.sumOf { it.sizeMB }
    }

    /**
     * Returns true if there is exactly one partition that fills remaining space.
     */
    fun hasFillPartition(): Boolean {
        return partitions.any { it.sizeMB == -1 }
    }

    /**
     * Returns the number of partitions that fill remaining space.
     */
    fun fillPartitionCount(): Int {
        return partitions.count { it.sizeMB == -1 }
    }

    /**
     * Validates that the partition layout is sane.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (partitions.isEmpty()) {
            errors.add("At least one partition is required")
        }

        val maxPartitions = if (tableType == PartitionTableType.MBR) 4 else 128
        if (partitions.size > maxPartitions) {
            errors.add("A maximum of $maxPartitions partitions is supported for ${tableType.displayName}")
        }

        if (fillPartitionCount() > 1) {
            errors.add("Only one partition can fill remaining space")
        }

        partitions.forEach { part ->
            if ((part.startLba == null) != (part.sizeSectors == null)) {
                errors.add("Partition '${part.label}' has incomplete repaired geometry")
            }
            if (part.startLba != null && (part.startLba < 1 || (part.sizeSectors ?: 0) < 1)) {
                errors.add("Partition '${part.label}' has invalid repaired geometry")
            }
        }

        val espPartitions = partitions.filter { it.isESP }
        if (espPartitions.size > 1) {
            errors.add("Only one EFI System Partition is allowed")
        }
        espPartitions.forEach { part ->
            if (part.filesystem != Filesystem.FAT32) {
                errors.add("ESP partition '${part.label}' must be FAT32")
            }
        }

        return errors
    }

    companion object {
        // ── Presets ──────────────────────────────────────────────

        /** ESP (FAT32) + a large exFAT data partition - readable on Windows/macOS/Linux. */
        fun multibootPreset(): LayoutConfig {
            return LayoutConfig(
                partitions = listOf(
                    PartitionDefinition(
                        id = 1,
                        label = "ESP",
                        sizeMB = 500,
                        filesystem = Filesystem.FAT32,
                        isESP = true
                    ),
                    PartitionDefinition(
                        id = 2,
                        label = "DATA",
                        sizeMB = -1,
                        filesystem = Filesystem.EXFAT
                    )
                )
            )
        }

        fun singlePartitionPreset(fs: Filesystem = Filesystem.EXFAT): LayoutConfig {
            return LayoutConfig(
                partitions = listOf(
                    PartitionDefinition(
                        id = 1,
                        label = "DATA",
                        sizeMB = -1,
                        filesystem = fs
                    )
                )
            )
        }

        /** UEFI Windows To Go-ready disk layout; this does not deploy Windows. */
        fun windowsToGoReadyPreset(): LayoutConfig = LayoutConfig(
            partitions = listOf(
                PartitionDefinition(1, "ESP", 500, Filesystem.FAT32, isESP = true),
                PartitionDefinition(2, "WINDOWS", -1, Filesystem.NTFS)
            ),
            tableType = PartitionTableType.GPT
        )
    }
}
