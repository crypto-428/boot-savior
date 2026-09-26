package com.yourapp.USBooter.util

import android.util.Log

/**
 * Formats a USB drive by writing a partition table and filesystem(s) directly
 * over raw USB block access (via [device]) - no root, no shelling out to parted
 * or mkfs. [device] must already be open (permission granted, capacity known).
 *
 * When [bootConfig] and [isoSource] are supplied it also makes the drive
 * bootable: either by raw-cloning an isohybrid ISO over the whole device, or by
 * copying the ISO's file tree into the FAT32 target partition after formatting.
 */
class FormatEngine(
    private val device: UsbBulkStorageDevice,
    private val driveModel: String,
    private val config: LayoutConfig,
    private val bootConfig: BootConfig? = null,
    private val isoSource: IsoSource? = null,
    /** App assets, needed to read the bundled GRUB images for legacy BIOS boot. */
    private val assets: android.content.res.AssetManager? = null,
    /** Validate everything and compute the layout, but never write to the drive. */
    private val dryRun: Boolean = false
) {
    companion object {
        private const val TAG = "FormatEngine"
        private const val ALIGNMENT_SECTORS = 2048L // 1MiB at 512 bytes/sector, for alignment
    }

    private data class PlannedPartition(
        val definition: PartitionDefinition,
        val startLba: Long,
        val sizeInSectors: Long
    )

    @Volatile
    private var cancelled = false

    private var bootWriter: BootableWriter? = null
    private var analysedIsoInfo: IsoInfo? = null

    fun format(progressCallback: (String, Int, String) -> Unit): Boolean {
        try {
            val source = isoSource
            val boot = bootConfig

            // ── Bootable: inspect the image up front so we know which path to take ──
            var isoInfo: IsoInfo? = null
            var resolvedMode: BootMode? = null
            if (boot != null && source != null) {
                progressCallback("Analysing ${boot.isoDisplayName}...", 2, "Reading the ISO's boot records")
                isoInfo = IsoAnalyzer.analyze(source)
                analysedIsoInfo = isoInfo
                resolvedMode = isoInfo.resolveMode(boot.mode)

                if (boot.mode == BootMode.AUTO && !isoInfo.canExtract && isoInfo.isHybrid) {
                    resolvedMode = BootMode.RAW_CLONE
                }
                if (resolvedMode == BootMode.UEFI_FILE_COPY && boot.firmware == FirmwareTarget.BIOS) {
                    progressCallback(
                        "Error: this image cannot boot on legacy BIOS",
                        -1,
                        "It has no hybrid boot record, so only UEFI machines can start it. Pick UEFI as the target, or use another image."
                    )
                    return false
                }
                bootWriter = BootableWriter(device, source, isoInfo, boot)
                FlashJournal.begin(
                    deviceName = driveModel,
                    driveModel = driveModel,
                    isoName = boot.isoDisplayName,
                    isoUri = boot.isoUriString,
                    mode = (resolvedMode ?: BootMode.AUTO).name
                )
            }


            if (dryRun) return dryRunFlow(isoInfo, resolvedMode, progressCallback)

            // ── Universal mode owns the whole layout: MBR + FAT32 + GRUB 2 ──
            if (resolvedMode == BootMode.UNIVERSAL) {
                return universalFlow(isoInfo!!, progressCallback)
            }

            // ── Raw clone replaces the whole disk, so it skips partitioning entirely ──
            if (resolvedMode == BootMode.RAW_CLONE) {
                return rawCloneFlow(progressCallback)
            }

            val bootSteps = if (resolvedMode == BootMode.UEFI_FILE_COPY)
                (if (boot!!.verification.checksBootFiles) 2 else 1) else 0
            val totalSteps = 2 + config.partitions.size + bootSteps
            var currentStep = 0

            // ── Step 1: Wipe the old partition table / filesystem headers ──
            currentStep++
            progressCallback(
                "Wiping old partition table...",
                percent(currentStep, totalSteps),
                "Clearing the first few MB of the drive"
            )
            wipeStart(progressCallback)
            if (cancelled) return cancelled("Cancelled", progressCallback)

            // ── Step 2: Compute layout and write the partition table ────────
            currentStep++
            progressCallback(
                "Writing ${config.tableType.displayName} partition table...",
                percent(currentStep, totalSteps),
                "Creating ${config.partitions.size} partition(s)"
            )
            val planned = computePartitionLayout()
            val bootableIndex = if (resolvedMode == BootMode.UEFI_FILE_COPY) {
                runCatching { resolveBootPartitionIndex(computePartitionLayout(), boot!!.targetPartitionIndex) }
                    .getOrDefault(0)
            } else 0
            writePartitionTable(planned, bootableIndex)
            if (cancelled) return cancelled("Cancelled", progressCallback)

            // ── Step 3: Format each partition ────────────────────────────────
            planned.forEachIndexed { index, part ->
                currentStep++
                val partition = part.definition
                progressCallback(
                    "Formatting ${partition.label} as ${partition.filesystem.displayName}...",
                    percent(currentStep, totalSteps),
                    "Partition ${index + 1}: ${part.sizeInSectors * device.blockSize / 1_000_000} MB"
                )

                when (partition.filesystem) {
                    Filesystem.FAT32 -> Fat32Formatter.format(
                        device, part.startLba, part.sizeInSectors, partition.label
                    )
                    Filesystem.EXFAT -> ExfatFormatter.format(
                        device, part.startLba, part.sizeInSectors, partition.label
                    )
                    Filesystem.NTFS -> NtfsFormatter.format(
                        device, part.startLba, part.sizeInSectors, partition.label
                    )
                    Filesystem.FAT16, Filesystem.FAT12 -> FatLegacyFormatter.format(
                        device, part.startLba, part.sizeInSectors, partition.label,
                        partition.filesystem == Filesystem.FAT12
                    )
                    Filesystem.EXT4, Filesystem.EXT2, Filesystem.LINUX_SWAP -> LinuxFs.format(
                        device, part.startLba, part.sizeInSectors, partition.label, partition.filesystem
                    )
                    Filesystem.HFSPLUS -> HfsPlusFormatter.format(
                        device, part.startLba, part.sizeInSectors, partition.label
                    )
                }

                if (cancelled) return cancelled("Cancelled", progressCallback)
            }

            // ── Step 4 (optional): Copy the ISO's files onto the boot partition ──
            if (resolvedMode == BootMode.UEFI_FILE_COPY) {
                val bootPartitionIndex = resolveBootPartitionIndex(planned, boot!!.targetPartitionIndex)
                val target = planned[bootPartitionIndex]
                if (target.definition.filesystem != Filesystem.FAT32) {
                    throw IllegalStateException("The boot partition must be FAT32 (it is ${target.definition.filesystem.displayName})")
                }

                val stepBase = currentStep + 1
                progressCallback(
                    "Copying boot files to ${target.definition.label}...",
                    percent(stepBase, totalSteps),
                    "Extracting ${boot.isoDisplayName}"
                )
                // The bar used to sit still on a whole-step percentage (7 steps ->
                // stuck at 71%) while the copy and the read-back ran for minutes.
                // Both phases now map their own 0-100% onto the span they own.
                val copySpanStart = percent(stepBase - 1, totalSteps)
                val copySpanEnd = if (boot.verification.checksBootFiles) 88 else 97
                val copied = bootWriter!!.copyFilesToPartition(target.startLba) { pct, detail ->
                    progressCallback(
                        "Copying boot files to ${target.definition.label}... $pct%",
                        copySpanStart + (pct.coerceIn(0, 100) * (copySpanEnd - copySpanStart)) / 100,
                        detail
                    )
                }
                currentStep = stepBase
                if (!copied || cancelled) return cancelled("Cancelled", progressCallback)

                if (boot.verification.checksBootFiles) {
                    val isFull = boot.verification == VerificationLevel.FULL
                    currentStep++
                    progressCallback(
                        if (isFull) "Performing full verification..." else "Verifying boot files...",
                        89,
                        "Re-reading the target partition"
                    )
                    val problem = bootWriter!!.verifyBootFiles(target.startLba)
                    if (problem != null) {
                        progressCallback("Error: $problem", -1, "The drive was written, but it will not boot. Retry the flash.")
                        return false
                    }

                    // Content check: walk the FAT32 tree that was just written and
                    // confirm /.disk/info and the /boot files are really there and
                    // identical to the image, instead of trusting the copy loop.
                    val report = BootFileVerifier.verifyFileCopy(
                        device, target.startLba, isoSource!!, bootWriter!!.isoEntries(), isFull, { cancelled }
                    ) { pct, detail ->
                        val base = if (isFull) 89 else 90
                        progressCallback(
                            (if (isFull) "Full verification... " else "Checking boot files... ") + "$pct%",
                            base + (pct.coerceIn(0, 100) * (100 - base - 1)) / 100,
                            detail
                        )
                    }
                    if (!report.ok) {
                        progressCallback(
                            "Error: verification failed",
                            -1,
                            report.problems.joinToString(" · ")
                        )
                        return false
                    }
                    report.notes.forEach { Log.w(TAG, it) }
                }

                device.synchronizeCache()
                FlashJournal.complete()
                progressCallback(
                    "Bootable drive ready!",
                    100,
                    "${boot.isoDisplayName} copied to ${target.definition.label} - boot this drive in UEFI mode"
                )
                return true
            }

            device.synchronizeCache()
            FlashJournal.complete()
            progressCallback(
                "Format complete!",
                100,
                "Successfully created ${config.partitions.size} partition(s) on $driveModel"
            )
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Format failed", e)
            FlashJournal.interrupted(e.message ?: "exception")
            progressCallback("Error: ${e.message}", -1, e.stackTraceToString())
            return false
        }
    }

    fun cancel() {
        cancelled = true
        bootWriter?.cancel()
    }

    /**
     * Dry run: everything a real flash does *except* writing. It validates the
     * bundled bootloader assets, re-reads the image's directory structure,
     * computes the exact partition layout that would be created, checks the
     * drive answers reads across its whole capacity, and confirms the payload
     * fits. Nothing on the drive is modified.
     */
    private fun dryRunFlow(
        isoInfo: IsoInfo?,
        resolvedMode: BootMode?,
        progressCallback: (String, Int, String) -> Unit
    ): Boolean {
        // A dry run writes nothing, so it must not leave a pending journal behind.
        FlashJournal.clear()
        val blockSize = device.blockSize
        val totalBytes = device.totalBlocks * blockSize
        val lines = mutableListOf<String>()

        fun note(text: String) {
            lines.add(text)
            FlashReport.step("Dry run", text)
        }

        progressCallback("Dry run: inspecting the drive...", 10, "$driveModel, ${totalBytes / 1_000_000} MB, $blockSize B sectors")
        note("Drive: $driveModel, ${device.totalBlocks} sectors of $blockSize B (${totalBytes / 1_000_000} MB)")

        // 1. Bundled assets
        progressCallback("Dry run: validating bundled bootloader...", 25, "Hashing grub_boot.img and grub_core.img")
        val assetManager = assets
        if (assetManager != null) {
            val summary = runCatching { GrubBiosInstaller.validateAssets(assetManager) }
            if (summary.isFailure) {
                progressCallback(
                    "Error: bundled bootloader assets are damaged", -1,
                    summary.exceptionOrNull()?.message ?: "unknown asset problem"
                )
                return false
            }
            note("Bootloader assets: ${summary.getOrNull()}")
        } else {
            note("Bootloader assets: not checked (no asset manager)")
        }

        // 2. Image
        if (isoInfo != null) {
            note("Image: ${isoInfo.displayName}, ${isoInfo.sizeBytes / 1_000_000} MB, ${isoInfo.directoryFormat} tree with ${isoInfo.fileCount} files")
            note("Largest file: ${isoInfo.largestFileBytes / (1024 * 1024)} MB; hybrid=${isoInfo.isHybrid}; EFI loader=${isoInfo.hasEfiBootFile}")
            if (isoInfo.isWindows) note("Windows media: ${isoInfo.windowsLayoutSummary}")
            note("Write mode that would run: ${resolvedMode?.name ?: "FORMAT_ONLY"}")
        }

        // 3. Planned layout
        progressCallback("Dry run: computing the partition layout...", 50, "No data is written")
        if (resolvedMode == BootMode.UNIVERSAL && isoInfo != null) {
            if (isoInfo.needsSplitLayout) {
                val planned = runCatching { bootWriter?.planWimSplit() }.getOrNull()
                if (planned != null) {
                    note("install.wim would be split into ${planned.summary()} - one FAT32 partition, no exFAT")
                    if (planned.images.isNotEmpty()) {
                        note("Editions inside install.wim: " + planned.images.joinToString(", ") { it.label })
                    }
                } else {
                    note("install.wim cannot be split on-device; the FAT32 + exFAT layout would be used instead")
                }
            }
            val split = isoInfo.needsSplitLayout
            val oversized = maxOf(isoInfo.largestFileBytes, isoInfo.installImageBytes)
            val bootBytes = if (split) (isoInfo.sizeBytes - oversized).coerceAtLeast(0) else isoInfo.sizeBytes
            note("Partition 1: FAT32 boot partition at LBA $ALIGNMENT_SECTORS, needs about ${bootBytes / 1_000_000} MB")
            if (split) note("Partition 2: exFAT, holds the ${oversized / 1_000_000} MB install image")
            if (bootConfig!!.persistenceMB > 0 && isoInfo.supportsPersistence) {
                note("Persistence partition: ${bootConfig.persistenceMB} MB ext2 labelled ${isoInfo.persistenceLabel}")
            } else if (bootConfig!!.persistenceMB > 0) {
                note("Persistence requested but this image has no casper/live initrd - it would be skipped")
            }
            if (isoInfo.sizeBytes + 512L * 1024 * 1024 > totalBytes) {
                progressCallback("Error: the drive is too small", -1, "${isoInfo.sizeBytes / 1_000_000} MB of payload does not fit in ${totalBytes / 1_000_000} MB")
                return false
            }
        } else if (resolvedMode == BootMode.RAW_CLONE && isoInfo != null) {
            note("Raw clone would write ${isoInfo.sizeBytes / 1_000_000} MB from LBA 0; the remaining ${(totalBytes - isoInfo.sizeBytes) / 1_000_000} MB stays unallocated")
        } else {
            computePartitionLayout().forEachIndexed { index, part ->
                note("Partition ${index + 1}: ${part.definition.label} ${part.definition.filesystem.displayName} at LBA ${part.startLba}, ${part.sizeInSectors * blockSize / 1_000_000} MB")
            }
        }

        // 4. Read-back checks: can we actually talk to the whole medium?
        progressCallback("Dry run: read-back checks...", 75, "Reading sectors across the drive")
        val probes = listOf(0L, device.totalBlocks / 4, device.totalBlocks / 2, device.totalBlocks - 8)
        probes.filter { it in 0 until device.totalBlocks }.forEach { lba ->
            val data = runCatching { device.readBlocks(lba, 1) }
            if (data.isFailure) {
                progressCallback("Error: the drive stopped answering reads", -1, "LBA $lba: ${data.exceptionOrNull()?.message}")
                return false
            }
            note("Read LBA $lba: ok (${data.getOrNull()?.size} bytes)")
        }

        progressCallback("Dry run complete - nothing was written", 100, lines.joinToString(" · "))
        return true
    }

    // ── Bootable helpers ─────────────────────────────────────────────────

    private fun rawCloneFlow(progressCallback: (String, Int, String) -> Unit): Boolean {
        val boot = bootConfig!!
        val writer = bootWriter!!
        val info = analysedIsoInfo

        // ── Pre-flight: refuse images a raw clone can never boot ──────────
        // dd-ing a non-hybrid image produces a drive with no MBR boot code and
        // no partition table the firmware understands. That is exactly what a
        // Windows ISO is, and why raw clone "flashed fine but never booted".
        if (info != null && info.isWindows) {
            progressCallback(
                "Error: a Windows ISO cannot be raw-cloned",
                -1,
                "Windows installation media has no hybrid boot record: a byte-for-byte copy is not bootable on BIOS or UEFI. " +
                    "Choose the Windows mode (FAT32 boot partition + exFAT for install.wim) instead."
            )
            return false
        }
        if (info != null && !info.isHybrid) {
            progressCallback(
                "Error: this image is not raw-cloneable",
                -1,
                "There is no hybrid boot record in sector 0 of ${boot.isoDisplayName}, so a raw copy leaves the drive unbootable. " +
                    "Use Universal mode, which partitions the drive and installs GRUB 2."
            )
            return false
        }

        progressCallback("Wiping the start of the drive...", 3, "Clearing stale partition tables")
        wipeStart(progressCallback)
        if (cancelled) return cancelled("Cancelled", progressCallback)

        progressCallback("Writing ${boot.isoDisplayName}...", 5, "Raw image clone - this replaces the whole drive")
        val ok = writer.rawClone { pct, detail ->
            // Raw clone owns 5-85% of the bar, verification takes the rest.
            val scaled = 5 + (pct * 80) / 100
            progressCallback("Writing ${boot.isoDisplayName}... $pct%", scaled, detail)
        }
        if (!ok || cancelled) return cancelled("Cancelled", progressCallback)

        // Flush the device cache before reading anything back, otherwise the
        // verification compares against data that is still in the stick's buffer.
        device.synchronizeCache()

        // Always check the boot region, even when the user asked to skip checks:
        // a bridge that silently drops LBA-0 writes is the single most common
        // reason a raw clone "succeeds" and then does not boot, and it costs
        // less than a second to catch.
        progressCallback("Checking the boot region...", 78, "Re-reading the first 4 MB from the drive")
        val bootRegionProblem = verifyBootRegion()
        if (bootRegionProblem != null) {
            progressCallback("Error: $bootRegionProblem", -1, "The drive did not keep the boot sectors that were written to it.")
            return false
        }

        if (boot.verification.checksPayload) {
            progressCallback("Verifying written image...", 80, "Comparing SHA-256 of the written bytes")
            val matched = writer.verifyRawClone { pct, detail ->
                val scaled = 80 + (pct * 12) / 100
                progressCallback("Verifying written image... $pct%", scaled, detail)
            }
            if (cancelled) return cancelled("Cancelled", progressCallback)
            if (!matched) {
                progressCallback(
                    "Error: verification failed",
                    -1,
                    "The data read back from the drive does not match the ISO. The drive may be faulty or was disconnected."
                )
                return false
            }
        }

        // A raw image is an indivisible disk layout. Never relocate its GPT,
        // alter its protective/hybrid MBR, add an entry, or format its free tail.
        // Partition configuration belongs to file-copy mode only.
        device.synchronizeCache()

        // Post-flash content check: parse the ISO9660 volume back off the stick
        // and confirm /.disk/info plus the boot-critical /boot and /EFI/BOOT
        // files exist with the expected size and contents. This is exactly what
        // GRUB looks for, so success here means the drive really is bootable.
        if (boot.verification.checksBootFiles) {
            val isFull = boot.verification == VerificationLevel.FULL
            progressCallback(
                if (isFull) "Performing full verification..." else "Checking boot files on the drive...",
                93,
                "Reading files back from the stick"
            )
            val report = BootFileVerifier.verifyRawClone(device, isoSource!!, isFull, { cancelled }) { pct, detail ->
                progressCallback(
                    (if (isFull) "Full verification... " else "Checking boot files... ") + "$pct%",
                    93 + (pct * 6) / 100,
                    detail
                )
            }
            if (cancelled) return cancelled("Cancelled", progressCallback)
            if (!report.ok) {
                progressCallback(
                    "Error: verification failed",
                    -1,
                    report.problems.joinToString(" · ") + " — do not boot this drive; reconnect it and flash again."
                )
                return false
            }
            report.notes.forEach { Log.w(TAG, it) }
            progressCallback(if (isFull) "Full verification complete" else "Boot files verified", 99, report.summary())
        }

        device.synchronizeCache()
        FlashJournal.complete()
        progressCallback(
            "Bootable drive ready!",
            100,
            "${boot.isoDisplayName} written exactly to $driveModel; unused tail is intentionally unallocated"
        )
        return true
    }


    /**
     * The "medium" approach: partition the stick ourselves, extract the image's
     * files onto a FAT32 partition and install a real GRUB 2 bootloader in the
     * MBR gap. UEFI boots the extracted EFI/BOOT/BOOTX64.EFI directly; legacy
     * BIOS runs GRUB, which then chainloads whatever the image ships.
     *
     * Unlike a raw clone this never depends on the ISO having a hybrid MBR, it
     * never writes a partition table the firmware has to guess at, and it leaves
     * no unallocated space behind.
     */
    private fun universalFlow(
        isoInfo: IsoInfo,
        progressCallback: (String, Int, String) -> Unit
    ): Boolean {
        val boot = bootConfig!!
        val writer = bootWriter!!
        val source = isoSource!!
        val assetManager = assets
            ?: throw IllegalStateException("Bundled GRUB images are unavailable, cannot install the BIOS bootloader")

        val blockSize = device.blockSize
        val totalBlocks = device.totalBlocks

        // ── Windows media with an oversized install.wim ──────────────────
        // Preferred fix: split the WIM into a spanned install.swm set, so every
        // file stays under 4 GB and the *entire* installer lives on the single
        // FAT32 partition that firmware and WinPE are guaranteed to read.
        // The old FAT32 + exFAT layout is only used when the container cannot be
        // redistributed on-device (solid/LZMS resources, i.e. install.esd).
        val wimSplit = if (isoInfo.needsSplitLayout) {
            progressCallback(
                "Preparing install.wim for FAT32...",
                3,
                "Reading the WIM index so it can be written as install.swm parts"
            )
            writer.planWimSplit()
        } else null
        if (wimSplit != null) {
            FlashReport.fact(
                "install.wim split for FAT32",
                wimSplit.summary() +
                    (if (wimSplit.images.isNotEmpty())
                        " · editions: " + wimSplit.images.joinToString(", ") { it.label }
                    else "")
            )
        }

        val split = isoInfo.needsSplitLayout && wimSplit == null
        if (isoInfo.largestFileBytes >= IsoInfo.FAT32_FILE_LIMIT && !split && wimSplit == null) {
            progressCallback(
                "Error: a file in this image is too big for FAT32",
                -1,
                "'${isoInfo.largestFileBytes / (1024 * 1024)} MB' exceeds FAT32's 4 GB limit. Use an ISO that ships install.esd instead of install.wim, or flash it with raw image clone."
            )
            return false
        }

        // Every LBA below comes from LayoutMath, the same pure code the unit
        // tests exercise, so the layout on the stick can never diverge from the
        // layout the dry run and the tests describe.
        val usePersistence = boot.persistenceMB > 0 && isoInfo.supportsPersistence && !split
        val oversizedBytes = maxOf(isoInfo.largestFileBytes, isoInfo.installImageBytes)
        val layout = LayoutMath.planUniversal(
            totalBlocks = totalBlocks,
            blockSize = blockSize,
            payloadBytes = isoInfo.sizeBytes,
            oversizedBytes = oversizedBytes,
            split = split,
            persistenceMB = if (usePersistence) boot.persistenceMB else 0,
            useRemainingSpace = boot.useRemainingSpace,
            bootPartitionMBOverride = boot.bootPartitionMB
        )
        if (!layout.fits) {
            progressCallback("Error: the requested layout does not fit on this drive", -1, layout.problem ?: "")
            return false
        }
        val firstStart = layout.bootStart
        val bootSectors = layout.bootSectors
        val persistenceSectors = layout.persistenceSectors
        val persistenceStart = layout.persistenceStart
        val dataStart = layout.dataStart
        val dataSectors = layout.dataSectors
        val wantsData = layout.hasData
        FlashReport.fact(
            "Planned layout",
            "boot LBA $firstStart..${firstStart + bootSectors - 1}" +
                (if (persistenceSectors > 0) ", persistence LBA $persistenceStart..${persistenceStart + persistenceSectors - 1}" else "") +
                (if (dataSectors > 0) ", data LBA $dataStart..${dataStart + dataSectors - 1}" else "")
        )


        val volumeLabel = isoInfo.volumeLabel.ifBlank { "USBOOTER" }
        CopyDiagnostics.record(
            "Write mode",
            "UNIVERSAL, ${if (split) "FAT32 boot + exFAT data (Windows split)" else "single FAT32 partition"}, " +
                "volume label $volumeLabel"
        )

        progressCallback("Preparing the drive...", 4, "Clearing old partition tables and boot records")
        wipeStart(progressCallback)
        if (cancelled) return cancelled("Cancelled", progressCallback)

        progressCallback("Writing MBR partition table...", 8, if (wantsData) "Boot partition + data partition" else "One FAT32 boot partition")
        val entries = mutableListOf(
            MbrPartitionEntry(firstStart, bootSectors, Filesystem.FAT32, isESP = false, bootable = true)
        )
        val dataFilesystem = if (split) Filesystem.EXFAT else boot.dataPartitionFilesystem
        if (usePersistence && persistenceSectors > 0) {
            // 0x83 "Linux" so the kernel offers it to the live initrd.
            entries.add(
                MbrPartitionEntry(
                    persistenceStart, persistenceSectors, Filesystem.FAT32,
                    isESP = false, typeOverride = 0x83.toByte()
                )
            )
        }
        if (wantsData && dataSectors > 0) {
            entries.add(MbrPartitionEntry(dataStart, dataSectors, dataFilesystem, isESP = false))
        }
        if (split && dataSectors <= 0) {
            progressCallback(
                "Error: not enough room for the Windows layout",
                -1,
                "This image needs a FAT32 boot partition plus an exFAT partition for install.wim. Use a larger drive."
            )
            return false
        }
        // No chainloader here: GRUB's boot.img takes over sector 0 further down.
        FlashJournal.stage(FlashJournal.Stage.WIPED)
        Mbr.write(device, entries, installBootCode = false)
        FlashJournal.stage(FlashJournal.Stage.TABLE_WRITTEN, firstStart)
        if (cancelled) return cancelled("Cancelled", progressCallback)

        progressCallback("Formatting the boot partition (FAT32)...", 12, "Volume label $volumeLabel")
        Fat32Formatter.format(device, firstStart, bootSectors, volumeLabel)
        FlashJournal.stage(FlashJournal.Stage.FILESYSTEMS_READY, firstStart)

        if (usePersistence && persistenceSectors > 0) {
            progressCallback(
                "Creating the persistence partition...", 14,
                "ext2, ${persistenceSectors * blockSize / 1_000_000} MB, label ${isoInfo.persistenceLabel}"
            )
            val conf = if (isoInfo.persistenceFamily == "live")
                ("persistence.conf" to
                    GrubConfigBuilder.persistenceConf(isoInfo.persistenceFamily).toByteArray(Charsets.US_ASCII))
                else null
            Ext2Formatter.format(device, persistenceStart, persistenceSectors, isoInfo.persistenceLabel, conf)
            FlashReport.fact(
                "Persistence",
                "${persistenceSectors * blockSize / 1_000_000} MB ext2 labelled ${isoInfo.persistenceLabel}"
            )
        }

        if (wantsData && dataSectors > 0) {
            progressCallback("Formatting the data partition...", 16, dataFilesystem.displayName)
            val dataLabel = boot.dataLabel.ifBlank { if (split) "WINDOWS" else "DATA" }
            when (dataFilesystem) {
                Filesystem.FAT32 -> Fat32Formatter.format(device, dataStart, dataSectors, dataLabel)
                Filesystem.EXFAT -> ExfatFormatter.format(device, dataStart, dataSectors, dataLabel)
                Filesystem.NTFS -> NtfsFormatter.format(device, dataStart, dataSectors, dataLabel)
                Filesystem.FAT16, Filesystem.FAT12 -> FatLegacyFormatter.format(
                    device, dataStart, dataSectors, dataLabel, dataFilesystem == Filesystem.FAT12
                )
                Filesystem.EXT4, Filesystem.EXT2, Filesystem.LINUX_SWAP -> LinuxFs.format(device, dataStart, dataSectors, dataLabel, dataFilesystem)
                Filesystem.HFSPLUS -> HfsPlusFormatter.format(device, dataStart, dataSectors, dataLabel)
            }
        }
        if (cancelled) return cancelled("Cancelled", progressCallback)

        // GRUB menu, generated from what the image really contains.
        val grubCfg = GrubConfigBuilder.build(writer.isoEntries(), volumeLabel, boot.isoDisplayName, usePersistence)
        val extras = listOf(
            GrubBiosInstaller.CONFIG_PATH to grubCfg.toByteArray(Charsets.US_ASCII),
            "usbooter/README.txt" to (
                "Created by USBooter from ${boot.isoDisplayName}.\r\n" +
                    "Legacy BIOS boots through GRUB 2 (usbooter/grub/grub.cfg).\r\n" +
                    "UEFI boots EFI/BOOT/BOOTX64.EFI directly.\r\n"
                ).toByteArray(Charsets.US_ASCII)
        )

        progressCallback("Copying ${boot.isoDisplayName}...", 20, "Extracting the image onto the boot partition")
        val onCopyProgress: (Int, String) -> Unit = { pct, detail ->
            progressCallback("Copying ${boot.isoDisplayName}... $pct%", 20 + (pct * 60) / 100, detail)
        }
        FlashJournal.stage(FlashJournal.Stage.COPYING, firstStart)
        val copied = if (split) {
            writer.copyFilesSplit(firstStart, dataStart, extraFiles = extras, progress = onCopyProgress)
        } else {
            writer.copyFilesToPartition(firstStart, extras, wimSplit, onCopyProgress)
        }
        if (!copied || cancelled) return cancelled("Cancelled", progressCallback)
        device.synchronizeCache()

        // ── Post-copy content check for Windows media ────────────────────
        // Re-scan the drive itself and confirm the files Windows Setup and the
        // firmware need are really there, complete and byte-identical. Without
        // this a copy that silently dropped install.wim looked like a success and
        // only failed hours later, in front of the BIOS.
        if (isoInfo.isWindows) {
            progressCallback("Checking the Windows files on the drive...", 82, "bootmgr, EFI loader and sources/install.wim")
            val windows = WindowsMediaVerifier.verify(
                device, firstStart, source, writer.isoEntries(), writer.largePlacements,
                deepHash = boot.verification == VerificationLevel.FULL,
                replacedInstallPath = wimSplit?.sourcePath ?: "",
                generatedSplitFiles = writer.generatedSplitFiles,
                isCancelled = { cancelled }
            ) { pct, detail ->
                progressCallback("Checking the Windows files on the drive... $pct%", 82 + (pct * 7) / 100, detail)
            }
            if (cancelled) return cancelled("Cancelled", progressCallback)
            CopyDiagnostics.record(
                "Windows file check",
                if (windows.ok) windows.summary() else "failed - " + windows.problems.joinToString(" · ")
            )
            if (!windows.ok) {
                progressCallback(
                    "Error: the required Windows files are missing or damaged", -1,
                    windows.problems.joinToString(" · ")
                )
                return false
            }
            FlashReport.step("Windows files verified", windows.summary())
        }

        progressCallback("Installing the legacy BIOS bootloader...", 84, "Writing GRUB 2 into the MBR gap")
        FlashJournal.stage(FlashJournal.Stage.COPY_DONE, firstStart)
        GrubBiosInstaller.install(device, assetManager) { step ->
            progressCallback("Installing the legacy BIOS bootloader...", 84, step)
        }

        if (boot.verification.checksBootFiles) {
            val isFull = boot.verification == VerificationLevel.FULL
            progressCallback(
                if (isFull) "Performing full verification..." else "Checking boot files on the drive...",
                90,
                "Re-reading the written partition"
            )
            val problem = writer.verifyBootFiles(firstStart)
            if (problem != null && !problem.startsWith("Copied,")) {
                progressCallback("Error: $problem", -1, "The drive was written, but it will not boot. Retry the flash.")
                return false
            }
            val report = BootFileVerifier.verifyFileCopy(
                device, firstStart, source, writer.isoEntries(), isFull, { cancelled },
                skipPaths = wimSplit?.let { setOf(it.sourcePath) } ?: emptySet()
            ) { pct, detail ->
                progressCallback(
                    (if (isFull) "Full verification... " else "Checking boot files... ") + "$pct%",
                    90 + (pct * 8) / 100,
                    detail
                )
            }
            if (cancelled) return cancelled("Cancelled", progressCallback)
            if (!report.ok) {
                progressCallback("Error: verification failed", -1, report.problems.joinToString(" · "))
                return false
            }
            report.notes.forEach { Log.w(TAG, it) }

            // UEFI-specific: re-read EFI/BOOT/BOOT*.EFI off the FAT32 partition
            // and hash it in full - that file is the only thing UEFI firmware
            // starts, so it gets its own check and its own report entry.
            val efiFat = runCatching { Fat32Reader(device, firstStart) }.getOrNull()
            if (efiFat != null) {
                val (efiReport, results) = BootFileVerifier.verifyEfiLoaders(
                    source, writer.isoEntries(),
                    { path ->
                        val found = runCatching { efiFat.find(path) }.getOrNull()
                        if (found == null) null
                        else found.size to { off: Long, len: Int -> efiFat.read(found, off, len) }
                    }
                ) { pct, detail ->
                    progressCallback("Verifying the UEFI bootloader... $pct%", 98, detail)
                }
                results.forEach {
                    FlashReport.step(
                        "UEFI loader /${it.path}",
                        if (it.present && it.sizeOk) "verified, SHA-256 ${it.sha256}" else "MISSING or wrong size"
                    )
                }
                if (!efiReport.ok) {
                    progressCallback("Error: the UEFI bootloader did not verify", -1, efiReport.problems.joinToString(" · "))
                    return false
                }
            }
        }

        device.synchronizeCache()
        val bootsOn = when {
            isoInfo.canBiosBootUniversal && isoInfo.hasEfiBootFile -> "legacy BIOS and UEFI"
            isoInfo.canBiosBootUniversal -> "legacy BIOS"
            else -> "UEFI"
        }
        FlashJournal.complete()
        progressCallback(
            "Bootable drive ready!",
            100,
            "${boot.isoDisplayName} installed on $driveModel - boots on $bootsOn" +
                (if (usePersistence) ", with a ${persistenceSectors * blockSize / 1_000_000} MB ${isoInfo.persistenceLabel} persistence partition" else "") +
                when {
                    wimSplit != null ->
                        ", with install.wim written as ${wimSplit.parts.size} install.swm part(s) on the FAT32 partition"
                    split -> ", with install.wim on a second exFAT partition"
                    wantsData -> ", plus a ${dataFilesystem.displayName} data partition"
                    else -> ""
                }
        )
        return true
    }

    /**
     * Re-reads the first 4 MB of the drive and compares it with the image.
     * Also asserts the 0x55AA signature and at least one non-empty MBR entry -
     * the two things a BIOS needs before it will even try to boot the stick.
     */
    private fun verifyBootRegion(): String? {
        val source = isoSource ?: return null
        val blockSize = device.blockSize
        val bytes = minOf(4L * 1024 * 1024, source.size)
        val blocks = ((bytes + blockSize - 1) / blockSize).toInt()
        val onDrive = device.readBlocks(0, blocks)
        val expected = source.readAt(0, blocks * blockSize)

        val limit = minOf(bytes, onDrive.size.toLong()).toInt()
        for (i in 0 until limit) {
            if (onDrive[i] != expected[i]) {
                FlashReport.mismatch(
                    "raw clone boot region", (i / blockSize).toLong(), i.toLong(),
                    "0x%02X".format(expected[i]), "0x%02X".format(onDrive[i])
                )
                return "the drive did not store the image's boot region (byte $i, LBA ${i / blockSize} differs)"
            }
        }
        if ((onDrive[510].toInt() and 0xFF) != 0x55 || (onDrive[511].toInt() and 0xFF) != 0xAA) {
            return "sector 0 on the drive has no 0x55AA boot signature"
        }
        val hasPartition = (0 until 4).any { i ->
            val base = 446 + i * 16
            (0 until 4).any { (onDrive[base + 12 + it].toInt() and 0xFF) != 0 }
        }
        if (!hasPartition) return "the cloned MBR contains no partition entry, so no firmware will boot it"
        return null
    }

    private fun alignUp(sectors: Long): Long = LayoutMath.alignUp(sectors)

    private fun alignDown(sectors: Long): Long = LayoutMath.alignDown(sectors)


    /** Prefers the ESP if one exists, otherwise the requested index, otherwise the first FAT32 partition. */
    private fun resolveBootPartitionIndex(planned: List<PlannedPartition>, requested: Int): Int {
        val espIndex = planned.indexOfFirst { it.definition.isESP }
        if (espIndex >= 0) return espIndex
        if (requested in planned.indices && planned[requested].definition.filesystem == Filesystem.FAT32) return requested
        val fat = planned.indexOfFirst { it.definition.filesystem == Filesystem.FAT32 }
        if (fat >= 0) return fat
        throw IllegalStateException("No FAT32 partition available to hold the boot files")
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun cancelled(status: String, progressCallback: (String, Int, String) -> Unit): Boolean {
        FlashJournal.interrupted(status)
        progressCallback(status, -1, "Operation cancelled by user")
        return false
    }

    /**
     * Zeroes the first few MB so no stale partition table or filesystem signature remains.
     * With deep format enabled, every sector of the drive is overwritten instead.
     */
    private fun wipeStart(progressCallback: (String, Int, String) -> Unit) {
        val deep = config.deepFormat
        val sectorsToWipe = if (deep) device.totalBlocks
            else (10L * 1024 * 1024 / device.blockSize).coerceAtMost(device.totalBlocks)
        val chunk = if (deep) (4L * 1024 * 1024 / device.blockSize).toInt().coerceAtLeast(1) else 512
        var lba = 0L
        var lastPct = -1
        while (lba < sectorsToWipe) {
            val count = minOf(chunk.toLong(), sectorsToWipe - lba).toInt()
            try {
                device.writeZeroBlocks(lba, count)
            } catch (e: java.io.IOException) {
                if (!deep) throw e
                // Give an overheated/busy stick time to recover, then retry once.
                Thread.sleep(3000)
                device.writeZeroBlocks(lba, count)
            }
            lba += count
            // Let cheap sticks drain their write cache regularly during a deep wipe.
            if (deep && lba % (256L * 1024 * 1024 / device.blockSize) < count) {
                runCatching { device.synchronizeCache() }
            }
            if (cancelled) return
            if (deep) {
                val pct = (lba * 100 / sectorsToWipe).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    val mb = 1024L * 1024
                    progressCallback(
                        "Deep format: erasing the whole drive ($pct%)", 2,
                        "Erased ${lba * device.blockSize / mb} MB of ${sectorsToWipe * device.blockSize / mb} MB"
                    )
                }
            }
        }
    }

    private fun writePartitionTable(planned: List<PlannedPartition>, bootableIndex: Int = -1) {
        when (config.tableType) {
            PartitionTableType.MBR -> Mbr.write(
                device,
                planned.mapIndexed { index, it ->
                    MbrPartitionEntry(
                        it.startLba, it.sizeInSectors, it.definition.filesystem, it.definition.isESP,
                        bootable = index == bootableIndex
                    )
                },
                // Legacy BIOS machines need bootstrap code at LBA 0; UEFI ignores it.
                installBootCode = true
            )
            PartitionTableType.GPT -> Gpt.write(
                device,
                planned.map {
                    GptPartitionEntry(
                        it.startLba, it.sizeInSectors, it.definition.filesystem,
                        it.definition.isESP, it.definition.label
                    )
                }
            )
        }
    }

    private fun computePartitionLayout(): List<PlannedPartition> {
        val blockSize = device.blockSize

        val explicit = config.partitions.mapNotNull { partition ->
            val start = partition.startLba
            val size = partition.sizeSectors
            if (start == null || size == null) null else PlannedPartition(partition, start, size)
        }
        if (explicit.isNotEmpty()) {
            require(explicit.size == config.partitions.size) { "Repaired geometry must be supplied for every partition" }
            explicit.sortedBy { it.startLba }.forEachIndexed { index, part ->
                val reservedEnd = if (config.tableType == PartitionTableType.GPT) {
                    Gpt.reservedSectorsAtEnd(blockSize)
                } else 0L
                require(part.startLba > 0 && part.sizeInSectors > 0) { "Invalid repaired partition geometry" }
                require(part.startLba + part.sizeInSectors <= device.totalBlocks - reservedEnd) {
                    "Repaired partition geometry extends past the drive"
                }
                if (index > 0) {
                    val previous = explicit.sortedBy { it.startLba }[index - 1]
                    require(previous.startLba + previous.sizeInSectors <= part.startLba) {
                        "Repaired partitions overlap"
                    }
                }
            }
            return explicit
        }

        val startCursor = when (config.tableType) {
            PartitionTableType.MBR -> ALIGNMENT_SECTORS
            PartitionTableType.GPT -> maxOf(ALIGNMENT_SECTORS, Gpt.reservedSectorsAtStart(blockSize))
        }
        val trailingReserved = when (config.tableType) {
            PartitionTableType.MBR -> 0L
            PartitionTableType.GPT -> Gpt.reservedSectorsAtEnd(blockSize)
        }

        val ranges = LayoutMath.planSequential(
            totalSectors = device.totalBlocks,
            blockSize = blockSize,
            sizesMB = config.partitions.map { it.sizeMB },
            startCursor = startCursor,
            trailingReserved = trailingReserved
        )
        return config.partitions.mapIndexed { index, partition ->
            PlannedPartition(partition, ranges[index].startLba, ranges[index].sizeInSectors)
        }
    }

    private fun megabytesToSectors(mb: Int, blockSize: Int): Long =
        LayoutMath.megabytesToSectors(mb, blockSize)


    private fun percent(current: Int, total: Int): Int {
        return if (total == 0) 0 else ((current.toDouble() / total.toDouble()) * 100).toInt()
    }
}
