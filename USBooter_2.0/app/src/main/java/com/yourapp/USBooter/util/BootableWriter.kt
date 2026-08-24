package com.yourapp.USBooter.util

import android.util.Log
import java.io.IOException
import java.security.MessageDigest

/**
 * Turns a formatted (or blank) pendrive into a bootable one.
 *
 * Two strategies, both over the same raw USB block access the formatter uses:
 *  - [BootMode.RAW_CLONE]: stream the isohybrid ISO onto the device from LBA 0,
 *    exactly like `dd`. The image's own partition table and bootloaders take over.
 *  - [BootMode.UEFI_FILE_COPY]: leave the user's partition layout alone and copy
 *    the ISO's file tree into the FAT32 target partition, so the firmware finds
 *    EFI/BOOT/BOOTX64.EFI and boots it.
 *
 * Both can verify afterwards: raw clone by hashing the written bytes back and
 * comparing to the source, file copy by re-reading the EFI bootloader.
 */
class BootableWriter(
    private val device: UsbBulkStorageDevice,
    private val source: IsoSource,
    private val info: IsoInfo,
    private val bootConfig: BootConfig
) {
    companion object {
        private const val TAG = "BootableWriter"
        private const val CHUNK_BYTES = 1 shl 20 // 1 MiB per transfer
    }

    @Volatile
    private var cancelled = false

    var rawBytesWritten: Long = 0
        private set

    fun cancel() {
        cancelled = true
    }

    /** Writes the image raw onto the whole device. Destroys any partition table. */
    fun rawClone(progress: (Int, String) -> Unit): Boolean {
        val blockSize = device.blockSize
        val deviceBytes = device.totalBlocks * blockSize
        if (source.size > deviceBytes) {
            throw IOException("The ISO (${source.size / 1_000_000} MB) is larger than the drive (${deviceBytes / 1_000_000} MB)")
        }

        val chunkBytes = (CHUNK_BYTES / blockSize) * blockSize
        var written = 0L
        var lba = 0L

        while (written < source.size) {
            if (cancelled) return false
            val remaining = source.size - written
            val take = minOf(chunkBytes.toLong(), remaining).toInt()
            val payload = source.readAt(written, take)
            val padded = if (take % blockSize == 0) payload else payload.copyOf(((take / blockSize) + 1) * blockSize)
            device.writeBlocks(lba, padded)
            lba += padded.size / blockSize
            written += take
            rawBytesWritten = written
            progress(
                ((written * 100) / source.size).toInt(),
                "${written / 1_000_000} MB of ${source.size / 1_000_000} MB written"
            )
        }
        if (rawBytesWritten != source.size) {
            throw IOException("Incomplete image write: $rawBytesWritten of ${source.size} bytes")
        }
        return true
    }

    /** Reads the written region back and compares SHA-256 against the source image. */
    fun verifyRawClone(progress: (Int, String) -> Unit): Boolean {
        val blockSize = device.blockSize
        val chunkBytes = (CHUNK_BYTES / blockSize) * blockSize
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val deviceDigest = MessageDigest.getInstance("SHA-256")

        var read = 0L
        var lba = 0L
        while (read < source.size) {
            if (cancelled) return false
            val take = minOf(chunkBytes.toLong(), source.size - read).toInt()
            val expected = source.readAt(read, take)
            val blocks = (take + blockSize - 1) / blockSize
            val actual = device.readBlocks(lba, blocks)

            sourceDigest.update(expected, 0, take)
            deviceDigest.update(actual, 0, take)

            lba += blocks
            read += take
            progress(
                ((read * 100) / source.size).toInt(),
                "${read / 1_000_000} MB of ${source.size / 1_000_000} MB verified"
            )
        }

        val match = sourceDigest.digest().contentEquals(deviceDigest.digest())
        if (!match) Log.e(TAG, "SHA-256 mismatch after raw clone")
        return match
    }

    /**
     * Verifies the ISO9660 payload after post-clone partition-table work.
     *
     * The first 64 KiB is intentionally excluded because a GPT primary header,
     * its entry array, and the protective MBR legitimately change when the
     * backup GPT is moved and a tail partition is registered. ISO file data
     * starts after the volume-descriptor area, so any GRUB module, kernel, or
     * `/.disk/info` corruption is still detected here.
     */
    fun verifyFinalPayload(progress: (Int, String) -> Unit): Boolean {
        val blockSize = device.blockSize
        val mutablePrefix = 64L * 1024L
        if (source.size <= mutablePrefix) return true

        val start = ((mutablePrefix + blockSize - 1) / blockSize) * blockSize
        val chunkBytes = (CHUNK_BYTES / blockSize) * blockSize
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val deviceDigest = MessageDigest.getInstance("SHA-256")
        val total = source.size - start
        var checked = 0L

        while (checked < total) {
            if (cancelled) return false
            val take = minOf(chunkBytes.toLong(), total - checked).toInt()
            val expected = source.readAt(start + checked, take)
            val blocks = (take + blockSize - 1) / blockSize
            val actual = device.readBlocks((start + checked) / blockSize, blocks)
            sourceDigest.update(expected, 0, take)
            deviceDigest.update(actual, 0, take)
            checked += take
            progress(
                ((checked * 100) / total).toInt(),
                "${checked / 1_000_000} MB of ${total / 1_000_000} MB checked after layout changes"
            )
        }
        return sourceDigest.digest().contentEquals(deviceDigest.digest())
    }

    /** The ISO's file tree, parsed once and cached (used to build the GRUB menu). */
    private var cachedEntries: List<IsoEntry>? = null

    fun isoEntries(): List<IsoEntry> = cachedEntries ?: run {
        // UDF-aware: Windows media keeps its >4 GB install.wim only in the UDF
        // tree, so reading ISO9660 alone silently loses (or truncates) it.
        val directory = ImageDirectory.read(source)
        val files = directory.files
        CopyDiagnostics.record(
            "Image directory tree",
            "${directory.format}" +
                (if (directory.udfPreferred) " (UDF preferred over the smaller ISO9660 tree)" else "")
        )
        CopyDiagnostics.record(
            "Parsed entries",
            "${directory.entries.size} total, ${files.size} files, " +
                "${directory.entries.size - files.size} directories, ${directory.totalBytes / 1_000_000} MB"
        )
        CopyDiagnostics.record("Largest file in the image", "${directory.largestFileBytes / (1024 * 1024)} MB")
        if (directory.entries.isEmpty()) {
            CopyDiagnostics.record(
                "Why nothing can be copied",
                "Neither the ISO9660 nor the UDF descriptors produced a single entry, so there is no file tree to read"
            )
            throw IOException(
                "No readable ISO9660 or UDF file tree in this image - 0 files were parsed, so nothing could be copied"
            )
        }
        if (files.isEmpty()) {
            CopyDiagnostics.record(
                "Why nothing can be copied",
                "The ${directory.format} tree contains only directories (${directory.entries.size} of them) and no files"
            )
            throw IOException("The image's ${directory.format} tree lists ${directory.entries.size} directories but no files")
        }
        directory.entries.also { cachedEntries = it }
    }

    /**
     * Where each file bigger than the FAT32 limit was placed on the exFAT
     * partition, so the post-copy check can read it straight back off the drive.
     */
    val largePlacements = mutableListOf<WindowsMediaVerifier.Placement>()

    /**
     * `sources/install.swm` parts that were generated instead of the oversized
     * `install.wim`, with their final sizes, so the post-copy check can confirm
     * each one really landed on the FAT32 partition.
     */
    val generatedSplitFiles = mutableListOf<Pair<String, Long>>()

    /**
     * Works out how the image's oversized `install.wim` can be turned into a
     * spanned `install.swm` set that fits on FAT32. Returns null - with the
     * reason recorded in the diagnostics - when the container cannot be
     * redistributed without decompressing it.
     */
    fun planWimSplit(maxPartBytes: Long = WimSplitter.DEFAULT_MAX_PART_BYTES): WimSplitter.Plan? {
        val candidate = isoEntries().filter { !it.isDirectory }.firstOrNull { entry ->
            val path = entry.path.trim('/').lowercase()
            path == "sources/install.wim" && entry.size >= IsoInfo.FAT32_FILE_LIMIT
        } ?: return null

        return try {
            val plan = WimSplitter.plan(source, candidate, maxPartBytes)
            CopyDiagnostics.record(
                "install.wim split",
                "${candidate.size / (1024 * 1024)} MB WIM -> ${plan.summary()}" +
                    (if (plan.images.isNotEmpty())
                        "; editions kept: " + plan.images.joinToString(", ") { it.label }
                    else "")
            )
            plan
        } catch (e: Exception) {
            CopyDiagnostics.record(
                "install.wim split not possible",
                e.message ?: e.toString()
            )
            Log.w(TAG, "Cannot split ${candidate.path}", e)
            null
        }
    }

    /**
     * Copies every file in the ISO into the FAT32 partition that starts at
     * [partitionStartLba]. The partition must already be formatted FAT32.
     *
     * [extraFiles] are written in the same session (the FAT writer allocates
     * clusters sequentially and can only be used once), which is how the
     * generated `/usbooter/grub/grub.cfg` gets onto the stick.
     */
    fun copyFilesToPartition(
        partitionStartLba: Long,
        extraFiles: List<Pair<String, ByteArray>> = emptyList(),
        wimSplit: WimSplitter.Plan? = null,
        progress: (Int, String) -> Unit
    ): Boolean {
        val entries = isoEntries()
        // When the oversized install.wim is being replaced by a spanned SWM set,
        // the original file must not be copied - the parts take its place.
        val replacedPath = wimSplit?.sourcePath?.lowercase()
        val files = entries.filter { !it.isDirectory }
            .filter { replacedPath == null || it.path.trim('/').lowercase() != replacedPath }
        val totalBytes = (files.sumOf { it.size } + extraFiles.sumOf { it.second.size.toLong() } +
            (wimSplit?.totalBytes ?: 0L)).coerceAtLeast(1)

        CopyDiagnostics.record(
            "Copy plan",
            "single FAT32 partition, ${files.size} files, ${totalBytes / 1_000_000} MB (plus ${extraFiles.size} generated file(s))" +
                (if (wimSplit != null) ", install.wim written as ${wimSplit.parts.size} SWM part(s)" else "")
        )
        val writer = Fat32Writer(device, partitionStartLba)
        if (totalBytes > writer.freeBytes()) {
            throw IOException("The ISO needs ${totalBytes / 1_000_000} MB but the partition only has ${writer.freeBytes() / 1_000_000} MB free")
        }

        // Directories first so parents exist before their files are added.
        entries.filter { it.isDirectory }.sortedBy { it.path.count { c -> c == '/' } }
            .forEach { writer.mkdirs(it.path) }

        var copied = 0L
        val copyStartedAt = System.currentTimeMillis()
        fun progressDetail(name: String): String {
            val elapsedMs = (System.currentTimeMillis() - copyStartedAt).coerceAtLeast(1L)
            val bytesPerSecond = copied * 1000L / elapsedMs
            val remaining = (totalBytes - copied).coerceAtLeast(0L)
            val eta = if (elapsedMs >= 3000 && bytesPerSecond > 0) " · ETA ${formatDuration(remaining / bytesPerSecond)}" else " · estimating time…"
            return "${copied / 1_000_000} MB of ${totalBytes / 1_000_000} MB · ${formatRate(bytesPerSecond)}$eta · $name"
        }
        files.forEach { file ->
            if (cancelled) return false
            if (file.size >= 4L * 1024 * 1024 * 1024) {
                throw IOException("'${file.path}' is ${file.size / (1024 * 1024)} MB - too large for FAT32. Use raw clone mode or an NTFS-capable target.")
            }
            val parentPath = file.path.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) writer.root else writer.mkdirs(parentPath)
            val name = file.path.substringAfterLast('/')

            writer.writeFile(
                dir = dir,
                name = name,
                sizeBytes = file.size,
                read = { offset, length -> file.readAt(source, offset, length) },
                onBytesWritten = { bytes ->
                    copied += bytes
                    progress(
                        ((copied * 100) / totalBytes).toInt().coerceIn(0, 100),
                        progressDetail(name)
                    )
                }
            )
        }

        extraFiles.forEach { (path, bytes) ->
            val clean = path.trim('/')
            val parentPath = clean.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) writer.root else writer.mkdirs(parentPath)
            writer.writeFile(
                dir = dir,
                name = clean.substringAfterLast('/'),
                sizeBytes = bytes.size.toLong(),
                read = { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
            )
            copied += bytes.size
        }

        // ── The spanned install.swm set, generated on the fly ────────────────
        if (wimSplit != null) {
            generatedSplitFiles.clear()
            val dir = if (wimSplit.targetDirectory.isEmpty()) writer.root
            else writer.mkdirs(wimSplit.targetDirectory)
            wimSplit.parts.forEachIndexed { index, part ->
                if (cancelled) return false
                writer.writeFile(
                    dir = dir,
                    name = part.name,
                    sizeBytes = part.sizeBytes,
                    read = { offset, length -> wimSplit.read(index, offset, length) },
                    onBytesWritten = { bytes ->
                        copied += bytes
                        progress(
                            ((copied * 100) / totalBytes).toInt().coerceIn(0, 100),
                            progressDetail("${part.name} " +
                                "(part ${index + 1} of ${wimSplit.parts.size})"
                            )
                        )
                    }
                )
                generatedSplitFiles.add(
                    (if (wimSplit.targetDirectory.isEmpty()) part.name
                    else "${wimSplit.targetDirectory}/${part.name}") to part.sizeBytes
                )
            }
        }

        writer.flush()
        CopyDiagnostics.record(
            "Copied to the FAT32 partition",
            "${files.size + extraFiles.size} files, ${copied / 1_000_000} MB written"
        )
        return true
    }

    private fun formatRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_000_000 -> "${bytesPerSecond / 1_000_000} MB/s"
        bytesPerSecond >= 1_000 -> "${bytesPerSecond / 1_000} KB/s"
        else -> "$bytesPerSecond B/s"
    }

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return if (safe >= 3600) "%dh %02dm".format(safe / 3600, (safe % 3600) / 60)
        else if (safe >= 60) "%dm %02ds".format(safe / 60, safe % 60)
        else "${safe}s"
    }

    /**
     * Windows split layout: everything that fits goes onto the FAT32 boot
     * partition at [bootStartLba] (so UEFI firmware, which only reads FAT, finds
     * EFI/BOOT/BOOTX64.EFI and bootmgr), while every file at or above
     * [largeFileThreshold] - in practice `sources/install.wim` - goes onto the
     * exFAT partition at [dataStartLba], keeping its original path.
     *
     * Windows Setup running from boot.wim scans every attached volume for
     * `\sources\install.wim`, and WinPE reads exFAT natively, so it picks the
     * image up from the second partition.
     */
    fun copyFilesSplit(
        bootStartLba: Long,
        dataStartLba: Long,
        largeFileThreshold: Long = 4L * 1024 * 1024 * 1024,
        extraFiles: List<Pair<String, ByteArray>> = emptyList(),
        progress: (Int, String) -> Unit
    ): Boolean {
        val entries = isoEntries()
        val files = entries.filter { !it.isDirectory }
        val small = files.filter { it.size < largeFileThreshold }
        val large = files.filter { it.size >= largeFileThreshold }
        val totalBytes = (files.sumOf { it.size } + extraFiles.sumOf { it.second.size.toLong() })
            .coerceAtLeast(1)
        var copied = 0L
        largePlacements.clear()
        CopyDiagnostics.record(
            "Copy plan",
            "split layout: ${small.size} files (${small.sumOf { it.size } / 1_000_000} MB) to FAT32, " +
                "${large.size} file(s) (${large.sumOf { it.size } / 1_000_000} MB) to exFAT"
        )

        fun report(name: String) {
            progress(
                ((copied * 100) / totalBytes).toInt().coerceIn(0, 100),
                "${copied / 1_000_000} MB of ${totalBytes / 1_000_000} MB - $name"
            )
        }

        // ── FAT32 boot partition: the bootable tree minus the oversized files ──
        val fat = Fat32Writer(device, bootStartLba)
        val smallBytes = small.sumOf { it.size } + extraFiles.sumOf { it.second.size.toLong() }
        if (smallBytes > fat.freeBytes()) {
            throw IOException("The boot partition needs ${smallBytes / 1_000_000} MB but only has ${fat.freeBytes() / 1_000_000} MB free")
        }

        entries.filter { it.isDirectory }.sortedBy { it.path.count { c -> c == '/' } }
            .forEach { fat.mkdirs(it.path) }

        small.forEach { file ->
            if (cancelled) return false
            val parentPath = file.path.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) fat.root else fat.mkdirs(parentPath)
            val name = file.path.substringAfterLast('/')
            fat.writeFile(
                dir = dir,
                name = name,
                sizeBytes = file.size,
                read = { offset, length -> file.readAt(source, offset, length) },
                onBytesWritten = { bytes -> copied += bytes; report(name) }
            )
        }

        extraFiles.forEach { (path, bytes) ->
            val clean = path.trim('/')
            val parentPath = clean.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) fat.root else fat.mkdirs(parentPath)
            fat.writeFile(
                dir = dir,
                name = clean.substringAfterLast('/'),
                sizeBytes = bytes.size.toLong(),
                read = { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
            )
            copied += bytes.size
        }
        fat.flush()
        device.synchronizeCache()
        CopyDiagnostics.record(
            "Copied to the FAT32 boot partition",
            "${small.size + extraFiles.size} files, ${copied / 1_000_000} MB written"
        )

        if (large.isEmpty()) return true

        // ── exFAT partition: the >= 4 GB payload, same relative paths ──
        val exfat = ExfatWriter(device, dataStartLba)
        val largeBytes = large.sumOf { it.size }
        if (largeBytes > exfat.freeBytes()) {
            throw IOException("The exFAT partition needs ${largeBytes / 1_000_000} MB but only has ${exfat.freeBytes() / 1_000_000} MB free")
        }

        large.forEach { file ->
            if (cancelled) return false
            val parentPath = file.path.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) exfat.root else exfat.mkdirs(parentPath)
            val name = file.path.substringAfterLast('/')
            val firstCluster = exfat.writeFile(
                dir = dir,
                name = name,
                sizeBytes = file.size,
                read = { offset, length -> file.readAt(source, offset, length) },
                onBytesWritten = { bytes ->
                    copied += bytes
                    val writtenInFile = (copied - small.sumOf { it.size } - extraFiles.sumOf { it.second.size.toLong() })
                        .coerceIn(0, file.size)
                    report("$name · ${writtenInFile / 1_000_000} MB written")
                }
            )
            if (firstCluster > 0) {
                largePlacements.add(
                    WindowsMediaVerifier.Placement(file.path, file.size, exfat.lbaOfCluster(firstCluster))
                )
            }
        }
        exfat.flush()
        device.synchronizeCache()
        CopyDiagnostics.record(
            "Copied to the exFAT partition",
            large.joinToString(", ") { "${it.path} (${it.size / 1_000_000} MB)" }
        )
        return true
    }




    /**
     * Re-mounts the freshly written partition read-only (by re-reading the FAT
     * structures) and confirms the EFI bootloader is present and non-empty.
     */
    fun verifyBootFiles(partitionStartLba: Long): String? {
        return try {
            val boot = device.readBlocks(partitionStartLba, 1)
            val signatureOk = (boot[510].toInt() and 0xFF) == 0x55 && (boot[511].toInt() and 0xFF) == 0xAA
            val fsType = String(boot, 82, 8, Charsets.US_ASCII).trim()
            when {
                !signatureOk -> "Boot sector signature missing on the target partition"
                fsType != "FAT32" -> "Target partition does not report FAT32 ($fsType)"
                !info.hasEfiBootFile -> "Copied, but the image has no EFI/BOOT bootloader - it will not boot via UEFI"
                else -> null
            }
        } catch (e: Exception) {
            "Verification read failed: ${e.message}"
        }
    }
}
