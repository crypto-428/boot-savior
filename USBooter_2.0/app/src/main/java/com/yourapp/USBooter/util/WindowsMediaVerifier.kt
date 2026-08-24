package com.yourapp.USBooter.util

import java.security.MessageDigest

/**
 * Post-copy check for Windows installation media: re-scans the pendrive that was
 * just written and confirms the files Windows Setup and the firmware actually
 * need are present, complete and identical to the image.
 *
 * The files are read back the way a bootloader would read them - through the
 * FAT32 directory tree on the boot partition, and (for `sources/install.wim`,
 * which is too big for FAT32) straight off the exFAT partition at the LBA the
 * writer placed it - so a pass here means the drive is really usable, not just
 * that the copy loop returned without throwing.
 */
object WindowsMediaVerifier {

    /** Where a large file ended up on the exFAT partition, recorded while copying. */
    data class Placement(val path: String, val sizeBytes: Long, val firstLba: Long)

    data class Result(
        val ok: Boolean,
        val problems: List<String>,
        val checked: List<String>
    ) {
        fun summary(): String =
            if (ok) "Verified ${checked.size} required Windows file(s): ${checked.joinToString(", ")}"
            else problems.joinToString(" · ")
    }

    /** Files Windows media cannot boot or install without. */
    private val REQUIRED_BOOT = listOf(
        "bootmgr",
        "bootmgr.efi",
        "boot/bcd",
        "efi/boot/bootx64.efi",
        "sources/boot.wim"
    )

    /**
     * [isoFiles] is the image's own file list, used both to know which of the
     * required files this image actually ships and to compare sizes/hashes.
     */
    fun verify(
        device: UsbBulkStorageDevice,
        bootPartitionStartLba: Long,
        source: IsoSource,
        isoFiles: List<IsoEntry>,
        placements: List<Placement>,
        deepHash: Boolean,
        /**
         * Path of the oversized install.wim that was replaced by a spanned
         * install.swm set (empty when the image was copied as-is). It is skipped
         * here because that file deliberately does not exist on the drive.
         */
        replacedInstallPath: String = "",
        /** The generated install.swm parts and their expected sizes. */
        generatedSplitFiles: List<Pair<String, Long>> = emptyList(),
        isCancelled: () -> Boolean = { false },
        progress: (Int, String) -> Unit = { _, _ -> }
    ): Result {
        val problems = mutableListOf<String>()
        val checked = mutableListOf<String>()

        val byPath = isoFiles.associateBy { it.path.trim('/').lowercase() }
        val replaced = replacedInstallPath.trim('/').lowercase()
        val installEntries = isoFiles.filter {
            val path = it.path.trim('/').lowercase()
            !it.isDirectory && (path == "sources/install.wim" || path == "sources/install.esd" ||
                (path.startsWith("sources/install") && path.endsWith(".swm")))
        }
        val expected = (REQUIRED_BOOT.mapNotNull { byPath[it] } + installEntries)
            .filter { replaced.isEmpty() || it.path.trim('/').lowercase() != replaced }
            .distinctBy { it.path.trim('/').lowercase() }
        if (expected.isEmpty() && generatedSplitFiles.isEmpty()) {
            return Result(true, emptyList(), emptyList())
        }

        val fat = runCatching { Fat32Reader(device, bootPartitionStartLba) }.getOrNull()
        if (fat == null) {
            return Result(false, listOf("The boot partition could not be re-read as FAT32 after the copy"), emptyList())
        }

        expected.forEachIndexed { index, entry ->
            if (isCancelled()) return Result(false, listOf("Cancelled during verification"), checked)
            val path = entry.path.trim('/')
            progress((index * 100) / expected.size, "Checking /$path on the drive")

            val placement = placements.firstOrNull { it.path.trim('/').equals(path, ignoreCase = true) }
            val problem = if (placement != null) {
                verifyRaw(device, source, entry, placement, deepHash)
            } else {
                verifyOnFat(fat, source, entry, deepHash)
            }
            if (problem != null) problems.add(problem) else checked.add("/$path")
        }

        // ── The generated install.swm set ────────────────────────────────
        // These files exist only on the drive, so they are checked by name and
        // size against the split plan, plus a WIM header sanity read.
        generatedSplitFiles.forEach { (path, size) ->
            if (isCancelled()) return Result(false, listOf("Cancelled during verification"), checked)
            val clean = path.trim('/')
            val found = runCatching { fat.find(clean) }.getOrNull()
            if (found == null) {
                problems.add("/$clean is missing from the boot partition - Setup would find no installation files")
                return@forEach
            }
            if (found.size != size) {
                problems.add("/$clean is ${found.size} bytes on the drive but the split plan produced $size bytes")
                return@forEach
            }
            val head = runCatching { fat.read(found, 0, 8) }.getOrNull()
            if (head == null || String(head, 0, 5, Charsets.US_ASCII) != "MSWIM") {
                problems.add("/$clean does not start with a WIM header, so Setup would reject it")
                return@forEach
            }
            checked.add("/$clean")
        }
        if (replaced.isNotEmpty() && generatedSplitFiles.isEmpty()) {
            problems.add("install.wim was supposed to be written as an install.swm set, but no part was produced")
        }

        // The whole point of the split layout: install.wim must exist somewhere.
        if (installEntries.isNotEmpty() && generatedSplitFiles.isEmpty() && checked.none {
                it.endsWith("install.wim", true) || it.endsWith("install.esd", true) || it.endsWith(".swm", true)
            } &&
            problems.none { it.contains("install", true) }
        ) {
            problems.add("The Windows install payload was never written to the drive - Setup would stop at 'no installation media'")
        }

        progress(100, if (problems.isEmpty()) "All required Windows files verified" else "Missing or damaged files found")
        return Result(problems.isEmpty(), problems, checked)
    }

    private fun verifyOnFat(
        fat: Fat32Reader,
        source: IsoSource,
        entry: IsoEntry,
        deepHash: Boolean
    ): String? {
        val path = entry.path.trim('/')
        val found = runCatching { fat.find(path) }.getOrNull()
            ?: return "/$path is missing from the boot partition"
        if (found.size != entry.size) {
            return "/$path is ${found.size} bytes on the drive but ${entry.size} bytes in the image"
        }
        val length = if (deepHash) entry.size else minOf(entry.size, 1L shl 20)
        val onDrive = MessageDigest.getInstance("SHA-256")
        val inImage = MessageDigest.getInstance("SHA-256")
        var done = 0L
        while (done < length) {
            val take = minOf(1L shl 20, length - done).toInt()
            val a = runCatching { fat.read(found, done, take) }.getOrNull()
                ?: return "/$path could not be read back from the drive at offset $done"
            val b = entry.readAt(source, done, take)
            onDrive.update(a, 0, take)
            inImage.update(b, 0, take)
            done += take
        }
        if (!onDrive.digest().contentEquals(inImage.digest())) {
            return "/$path does not match the image (contents differ in the first ${length / 1024} KB)"
        }
        return null
    }

    private fun verifyRaw(
        device: UsbBulkStorageDevice,
        source: IsoSource,
        entry: IsoEntry,
        placement: Placement,
        deepHash: Boolean
    ): String? {
        val path = entry.path.trim('/')
        if (placement.sizeBytes != entry.size) {
            return "/$path was written as ${placement.sizeBytes} bytes but the image holds ${entry.size} bytes"
        }
        val blockSize = device.blockSize
        val length = if (deepHash) entry.size else minOf(entry.size, 8L shl 20)
        val onDrive = MessageDigest.getInstance("SHA-256")
        val inImage = MessageDigest.getInstance("SHA-256")
        var done = 0L
        while (done < length) {
            val take = minOf(1L shl 20, length - done).toInt()
            val blocks = (take + blockSize - 1) / blockSize
            val lba = placement.firstLba + done / blockSize
            val a = runCatching { device.readBlocks(lba, blocks) }.getOrNull()
                ?: return "/$path could not be read back from the exFAT partition at offset $done (LBA $lba..${lba + blocks - 1})"
            val b = entry.readAt(source, done, take)
            onDrive.update(a, 0, take)
            inImage.update(b, 0, take)
            done += take
        }
        if (!onDrive.digest().contentEquals(inImage.digest())) {
            FlashReport.mismatch("/$path", placement.firstLba, 0, "image SHA-256", "drive SHA-256")
            return "/$path on the exFAT partition does not match the image (starting LBA ${placement.firstLba})"
        }
        return null
    }
}
