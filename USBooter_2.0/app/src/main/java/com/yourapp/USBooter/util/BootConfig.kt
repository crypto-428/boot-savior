package com.yourapp.USBooter.util

import java.io.Serializable

/**
 * How the ISO payload gets onto the pendrive.
 *
 * UNIVERSAL       - the recommended path. Partition the stick (MBR + one FAT32
 *                   partition), extract the ISO's file tree onto it, install a
 *                   real GRUB 2 BIOS bootloader into the MBR gap and generate a
 *                   menu that chainloads whatever the image ships (its own
 *                   grub.cfg, its isolinux.cfg, Windows' bootmgr, or a direct
 *                   kernel + initrd). Boots on legacy BIOS *and* UEFI, and the
 *                   whole capacity of the stick stays usable and writable.
 * RAW_CLONE       - byte-for-byte copy of an isohybrid ISO onto the raw device
 *                   (this is what `dd if=x.iso of=/dev/sdX` does). Depends
 *                   entirely on the image and leaves the rest of the drive
 *                   unallocated; several USB bridges refuse the LBA-0 writes it
 *                   needs, which is why it is no longer the default.
 * UEFI_FILE_COPY  - extract the file tree without installing a BIOS bootloader.
 *                   UEFI-only, kept for drives that must not have their MBR
 *                   boot code touched.
 * AUTO            - UNIVERSAL whenever the image can be extracted, RAW_CLONE
 *                   only as a last resort.
 */
enum class BootMode(val displayName: String) : Serializable {
    AUTO("Automatic"),
    UNIVERSAL("Universal - BIOS + UEFI (recommended)"),
    WINDOWS("Windows installer (single FAT32 partition, install.wim split into install.swm)"),
    RAW_CLONE("Raw image clone (image's own layout)"),
    UEFI_FILE_COPY("UEFI file copy (no BIOS bootloader)")
}

/**
 * Everything the format job needs to know about making the drive bootable.
 * [isoUriString] is a SAF content:// URI the user picked; the service resolves it.
 */
enum class FirmwareTarget(val displayName: String) : Serializable {
    /** Old machines / CSM: needs an MBR, an active partition and a BIOS bootloader. */
    BIOS("Legacy BIOS"),
    /** Modern machines: needs a FAT32 partition with EFI/BOOT/BOOT*.EFI. */
    UEFI("UEFI"),
    /** Both - what UNIVERSAL mode produces for almost every image. */
    BOTH("UEFI + Legacy BIOS")
}

/**
 * How thoroughly the finished drive is checked before the app declares success.
 * Every level still writes the same bytes; they only trade time for confidence.
 */
enum class VerificationLevel(val displayName: String, val description: String) : Serializable {
    /** No read-back at all. Fastest, but a bad write is only discovered at boot. */
    NONE("Skip checks (fastest)", "Writes and finishes immediately"),
    /** Re-read only the boot-critical files (/.disk/info, /boot, /EFI/BOOT). Seconds. */
    BOOT_FILES("Boot files only (fast)", "Checks /.disk/info and the key /boot files are present and intact"),
    /** Full SHA-256 of the whole payload plus the boot-file content check. */
    FULL("Full verification (slowest)", "Hashes every written byte, then checks the boot files");

    val checksPayload: Boolean get() = this == FULL
    val checksBootFiles: Boolean get() = this != NONE
}

data class BootConfig(
    val isoUriString: String,
    val isoDisplayName: String,
    val mode: BootMode = BootMode.AUTO,
    /** Which firmware the finished stick has to boot on. Drives MBR/GPT and mode. */
    val firmware: FirmwareTarget = FirmwareTarget.BOTH,
    /** Universal mode: format the space the image doesn't need as a second data partition. */
    val useRemainingSpace: Boolean = false,
    /** Filesystem for that second data partition. */
    val dataPartitionFilesystem: Filesystem = Filesystem.EXFAT,
    /** Index into [LayoutConfig.partitions] that receives the boot files (file-copy mode). */
    val targetPartitionIndex: Int = 0,
    /** Read the written bytes back and compare SHA-256 (raw clone) / re-read the boot files. */
    val verify: Boolean = true,
    /** How much post-flash checking to do. Fewer checks = faster flashing. */
    val verification: VerificationLevel = VerificationLevel.FULL,
    /**
     * Size in MB of an ext2 persistence partition for live Linux images.
     * 0 disables persistence. Ubuntu/casper mounts it as `casper-rw`, Debian
     * live as `persistence` (with the `/persistence.conf` we write into it).
     */
    val persistenceMB: Int = 0,
    /** Advanced: force the FAT32 boot partition size in MB. 0 = computed automatically. */
    val bootPartitionMB: Int = 0,
    /** Advanced: label of the second (data / exFAT) partition. */
    val dataLabel: String = ""
) : Serializable

/** What [IsoAnalyzer] could work out about the selected image. */
data class IsoInfo(
    val displayName: String,
    val sizeBytes: Long,
    val volumeLabel: String,
    /** ISO has a valid MBR in sector 0 -> isohybrid, safe to raw-clone. */
    val isHybrid: Boolean,
    /** El Torito boot catalog advertises an EFI (platform 0xEF) entry. */
    val hasEfiBoot: Boolean,
    /** El Torito boot catalog advertises an x86 BIOS (platform 0x00) entry. */
    val hasBiosBoot: Boolean,
    /** EFI/BOOT/BOOTX64.EFI (or BOOTIA32/BOOTAA64) exists in the file tree. */
    val hasEfiBootFile: Boolean,
    /** Puppy-family marker (puppy_*.sfs). */
    val isPuppyLinux: Boolean,
    /** Largest single file - anything >= 4 GiB cannot live on FAT32. */
    val largestFileBytes: Long,
    /** Windows install media: /bootmgr plus /sources/boot.wim. */
    val isWindows: Boolean = false,
    /** The image ships its own /boot/grub/grub.cfg. */
    val hasDistroGrubConfig: Boolean = false,
    /** The image ships an isolinux.cfg/syslinux.cfg GRUB can translate. */
    val hasIsolinuxConfig: Boolean = false,
    /** A vmlinuz/bzImage was found, so a direct kernel entry can be generated. */
    val hasKernel: Boolean = false,
    /** Which directory structure the file tree was read from: "UDF", "ISO9660", "none". */
    val directoryFormat: String = "ISO9660",
    /** Number of files found in the image. */
    val fileCount: Int = 0,
    /** Sum of every file's size in the image. */
    val payloadBytes: Long = 0L,
    /** Windows media: path of sources/install.wim (or .esd), empty if absent. */
    val installImagePath: String = "",
    /** Windows media: size of that install image - decides FAT32 vs. FAT32+exFAT. */
    val installImageBytes: Long = 0L,
    /** "casper" (Ubuntu family), "live" (Debian family) or "" when persistence is not supported. */
    val persistenceFamily: String = "",
    /** /bootmgr is present: Windows installer *or* a WinPE / recovery image. */
    val hasBootmgr: Boolean = false,
    /** Identified image family, used for the advice shown next to the write modes. */
    val profileId: String = "generic",
    val profileName: String = "Generic image",
    val profileCategory: String = "Generic image",
    val profileAdvice: String = "",
    /** Write mode the detected family works best with. */
    val profileMode: BootMode = BootMode.AUTO
) : Serializable {

    /** WinPE / WinRE / rescue media: bootmgr and boot.wim, but no install payload. */
    val isWindowsPe: Boolean get() = hasBootmgr && !isWindows


    /**
     * Windows media whose install.wim is 4 GB or more: it cannot live on FAT32,
     * so the drive gets a FAT32 boot partition plus an exFAT partition holding
     * the oversized file. Windows Setup finds \sources\install.wim on any
     * attached volume and reads exFAT natively.
     */
    val needsSplitLayout: Boolean
        get() = isWindows && maxOf(largestFileBytes, installImageBytes) >= FAT32_FILE_LIMIT

    /** True when the file tree can be extracted onto a FAT32 (+ exFAT) layout. */
    val canExtract: Boolean
        get() = fileCount > 0 &&
            (largestFileBytes < FAT32_FILE_LIMIT || needsSplitLayout) &&
            (hasEfiBootFile || hasDistroGrubConfig || hasIsolinuxConfig || hasKernel || isWindows || hasBootmgr)

    companion object {
        const val FAT32_FILE_LIMIT = 4L * 1024 * 1024 * 1024
    }

    /** Live images whose initrd can pick up a persistence volume. */
    val supportsPersistence: Boolean get() = persistenceFamily.isNotEmpty() && !isWindows

    /** Volume label the initrd of this family looks for. */
    val persistenceLabel: String get() = if (persistenceFamily == "live") "persistence" else "casper-rw"

    /**
     * True when USBooter can produce a legacy-BIOS boot path in UNIVERSAL mode.
     * WinPE / recovery images count: GRUB chainloads their /bootmgr with ntldr.
     */
    val canBiosBootUniversal: Boolean
        get() = hasDistroGrubConfig || hasIsolinuxConfig || hasKernel || isWindows || hasBootmgr

    /** One-line summary of what the Windows analyzer decided. */
    val windowsLayoutSummary: String
        get() = when {
            isWindowsPe ->
                "$profileName: WinPE media (bootmgr + sources/boot.wim, no install.wim), so a single FAT32 partition is enough."
            !isWindows -> ""
            installImageBytes <= 0 -> "Windows media, but no sources/install.wim or install.esd was found."
            needsSplitLayout ->
                "$installImagePath is ${installImageBytes / (1024 * 1024)} MB (over FAT32's 4 GB limit): " +
                    "it is split into install.swm parts under 4 GB so one FAT32 partition holds the whole installer."
            else ->
                "$installImagePath is ${installImageBytes / (1024 * 1024)} MB: one FAT32 partition is enough."
        }



    /**
     * The mode [BootMode.AUTO] resolves to for this image.
     *
     * AUTO first asks [ImageProfile] (carried here as [profileMode]) what the
     * detected image family boots best with, then checks that the mode is
     * actually feasible for this image and falls back when it is not:
     *  - a file-tree mode (UNIVERSAL / WINDOWS / UEFI_FILE_COPY) needs an
     *    extractable tree; without one an isohybrid image is raw-cloned and
     *    anything else is copied as plain EFI files;
     *  - RAW_CLONE needs a valid isohybrid MBR in the ISO, otherwise the tree
     *    is extracted instead;
     *  - UEFI_FILE_COPY needs an EFI loader in the tree.
     */
    fun resolveMode(requested: BootMode): BootMode {
        val target = when (requested) {
            BootMode.WINDOWS -> BootMode.UNIVERSAL
            BootMode.AUTO -> autoMode()
            else -> requested
        }
        return feasible(target)
    }

    /** Mode AUTO picks before the feasibility check. */
    private fun autoMode(): BootMode = when {
        // Windows installer media: the split-layout / spanned-WIM path.
        isWindows -> BootMode.UNIVERSAL
        // The detected family has an explicit recommendation.
        profileMode != BootMode.AUTO -> if (profileMode == BootMode.WINDOWS) BootMode.UNIVERSAL else profileMode
        canExtract -> BootMode.UNIVERSAL
        isHybrid -> BootMode.RAW_CLONE
        else -> BootMode.UEFI_FILE_COPY
    }

    /** Downgrades a mode this particular image cannot be written with. */
    private fun feasible(mode: BootMode): BootMode = when (mode) {
        BootMode.UNIVERSAL ->
            if (canExtract) BootMode.UNIVERSAL
            else if (isHybrid) BootMode.RAW_CLONE
            else BootMode.UEFI_FILE_COPY
        BootMode.RAW_CLONE ->
            if (isHybrid) BootMode.RAW_CLONE
            else if (canExtract) BootMode.UNIVERSAL
            else BootMode.UEFI_FILE_COPY
        BootMode.UEFI_FILE_COPY ->
            if (hasEfiBootFile) BootMode.UEFI_FILE_COPY
            else if (canExtract) BootMode.UNIVERSAL
            else if (isHybrid) BootMode.RAW_CLONE
            else BootMode.UEFI_FILE_COPY
        else -> mode
    }


    /** Human-readable blockers/warnings for the chosen mode. */
    fun warningsFor(mode: BootMode): List<String> {
        val out = mutableListOf<String>()
        when (mode) {
            BootMode.UNIVERSAL -> {
                if (needsSplitLayout) {
                    out.add(
                        "${installImagePath.ifBlank { "install.wim" }} is " +
                            "${maxOf(installImageBytes, largestFileBytes) / (1024 * 1024)} MB, too big for FAT32, so it is written as a " +
                            "spanned install.swm set (install.swm, install2.swm, ...) on one FAT32 partition. " +
                            "Windows Setup loads spanned images natively, and no exFAT partition is needed. " +
                            "Only if the file cannot be split on-device (install.esd / solid compression) does the drive fall back " +
                            "to a second exFAT partition."
                    )
                } else if (largestFileBytes >= FAT32_FILE_LIMIT) {
                    out.add(
                        "This image contains a ${largestFileBytes / (1024 * 1024)} MB file, " +
                            "which does not fit on FAT32. Use an ISO with install.esd, or raw image clone."
                    )
                }
                if (!canBiosBootUniversal) {
                    out.add("No BIOS bootloader configuration was found in this image, so the stick will boot on UEFI only.")
                }
                if (!hasEfiBootFile) {
                    out.add("No EFI/BOOT/BOOTX64.EFI in the file tree, so UEFI boot may not work for this image.")
                }
            }
            BootMode.RAW_CLONE -> {
                if (isWindows) {
                    out.add(
                        "Windows installation images are not raw-cloneable: they have no hybrid boot record, and a byte-for-byte " +
                            "copy produces a drive no firmware can start. Use the Windows or Universal mode instead."
                    )
                } else if (!isHybrid) {
                    out.add("This ISO has no hybrid MBR, so a raw clone probably won't boot. Use universal mode instead.")
                }
                out.add("Raw clone writes the image's own disk layout. Unused capacity stays unallocated and some USB bridges reject it - universal mode is the reliable choice.")
            }
            BootMode.UEFI_FILE_COPY -> {
                if (!hasEfiBootFile && !hasEfiBoot) {
                    out.add("No EFI/BOOT/BOOTX64.EFI found in this ISO - file copy produces a UEFI-bootable drive only if that file exists.")
                }
                if (largestFileBytes >= FAT32_FILE_LIMIT) {
                    out.add("This ISO contains a file of ${largestFileBytes / (1024 * 1024)} MB, which exceeds FAT32's 4 GB limit (typical for Windows install.wim).")
                }
                out.add("Legacy BIOS boot is not installed in this mode; choose universal mode for BIOS support.")
            }
            BootMode.WINDOWS -> {
                if (!isWindows) {
                    out.add("This image does not look like Windows installation media (no /bootmgr and /sources/boot.wim). Universal mode is the right choice for it.")
                }
                out.addAll(warningsFor(BootMode.UNIVERSAL))
            }
            BootMode.AUTO -> {}
        }
        return out
    }

    /** Extra warnings that depend on which firmware the stick has to boot on. */
    fun warningsFor(mode: BootMode, firmware: FirmwareTarget): List<String> {
        val out = warningsFor(mode).toMutableList()
        when (firmware) {
            FirmwareTarget.BIOS -> {
                if (mode == BootMode.UEFI_FILE_COPY) {
                    out.add("File-copy mode cannot produce a legacy BIOS stick - choose universal mode.")
                }
                if (mode == BootMode.UNIVERSAL && !canBiosBootUniversal) {
                    out.add("This image provides nothing GRUB can start on a legacy BIOS.")
                }
            }
            FirmwareTarget.UEFI -> {
                if (!hasEfiBoot && !hasEfiBootFile) {
                    out.add("This image contains no EFI bootloader, so it can only boot on legacy BIOS.")
                }
            }
            FirmwareTarget.BOTH -> {
                if (mode == BootMode.UEFI_FILE_COPY) {
                    out.add("File-copy mode boots on UEFI only; choose universal mode for BIOS as well.")
                }
            }
        }
        return out
    }
}
