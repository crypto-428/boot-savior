package com.yourapp.USBooter.util

import android.util.Log
import java.security.MessageDigest

/**
 * Post-flash content verification.
 *
 * Hashing the raw bytes proves the transfer was faithful, but it cannot prove
 * that a bootloader will *find* what it needs: that is what the
 * `file '/boot/' not found` and `no such device: /.disk/info` GRUB failures are
 * about. So after writing, this walks the finished pendrive the way firmware
 * does - parsing ISO9660 off the device for a raw clone, or FAT32 for a file
 * copy - and checks that the boot-critical files are present, non-empty and
 * byte-identical (sampled) to the ones inside the source image.
 */
object BootFileVerifier {

    private const val TAG = "BootFileVerifier"
    private const val SAMPLE_BYTES = 128 * 1024

    /**
     * A read-back check must never look frozen. Big payload files on a slow stick
     * can take minutes each, so the check runs against a wall-clock budget: boot
     * critical files are always done first, and if time runs out the remaining
     * spot-check files are reported as skipped instead of hanging the progress bar.
     */
    private const val TIME_BUDGET_MS = 3 * 60 * 1000L

    data class Report(
        val ok: Boolean,
        val checkedFiles: Int,
        val problems: List<String>,
        val notes: List<String>
    ) {
        fun summary(): String = when {
            problems.isNotEmpty() -> problems.first()
            checkedFiles == 0 -> "No boot-critical files could be located on the drive"
            else -> "$checkedFiles boot files verified on the drive"
        }
    }

    /**
     * Boot-critical paths, in priority order. `/.disk/info` is what Debian and
     * Ubuntu family installers probe for; `/boot` holds the kernel, initrd and
     * GRUB configuration; `/EFI/BOOT` holds the UEFI loader.
     */
    private fun selectCriticalFiles(entries: List<IsoEntry>): List<IsoEntry> {
        val files = entries.filter { !it.isDirectory }
        fun match(predicate: (String) -> Boolean) = files.filter { predicate(it.path.lowercase()) }

        val picked = LinkedHashSet<IsoEntry>()
        picked += match { it == ".disk/info" }
        picked += match { it.startsWith("efi/boot/") && it.endsWith(".efi") }
        picked += match { it.startsWith("boot/") && it.endsWith("grub.cfg") }
        picked += match { it.startsWith("boot/") && (it.contains("vmlinu") || it.contains("initr")) }
        picked += match { it.startsWith("boot/") && (it.endsWith(".efi") || it.endsWith(".img")) }
        picked += match { it.startsWith("isolinux/") && it.endsWith(".cfg") }
        // A couple of ordinary payload files as a spot check of the bulk data.
        // One large payload file as a spot check - hashing several multi-GB files
        // back off the stick is what made the check crawl.
        picked += files.filter { it.size in 1..(512L * 1024 * 1024) }
            .sortedByDescending { it.size }.take(1)
        return picked.take(14).toList()
    }

    /** Verifies the file tree of a raw-cloned image directly off the device. */
    fun verifyRawClone(
        device: UsbBulkStorageDevice,
        source: IsoSource,
        fullCheck: Boolean,
        isCancelled: () -> Boolean,
        progress: (Int, String) -> Unit
    ): Report {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val sourceReader = Iso9660Reader(source)
        if (!sourceReader.parse()) {
            return Report(true, 0, emptyList(), listOf("Image is not ISO9660, content check skipped"))
        }
        val allEntries = sourceReader.listAll().filter { !it.isDirectory }
        val expected = if (fullCheck) allEntries else selectCriticalFiles(allEntries)
        
        if (expected.isEmpty()) {
            return Report(true, 0, emptyList(), listOf("No files to check in this image"))
        }

        val deviceReader = DeviceByteReader(device, 0, source.size)
        val onDisk = Iso9660Reader(deviceReader)
        if (!onDisk.parse()) {
            return Report(false, 0, listOf("The drive does not present a readable ISO9660 volume - the image did not land correctly"), notes)
        }
        val actualByPath = onDisk.listAll().filter { !it.isDirectory }
            .associateBy { it.path.lowercase() }

        var checked = 0
        val deadline = if (fullCheck) Long.MAX_VALUE else (System.currentTimeMillis() + TIME_BUDGET_MS)
        expected.forEachIndexed { index, entry ->
            if (isCancelled()) return Report(false, checked, listOf("Cancelled"), notes)
            progress((index * 100) / expected.size, "Checking /${entry.path}")
            if (!fullCheck && System.currentTimeMillis() > deadline) {
                notes.add("Skipped ${expected.size - index} spot checks to keep the flash responsive")
                return@forEachIndexed
            }
            val actual = actualByPath[entry.path.lowercase()]
            when {
                actual == null ->
                    problems.add("'/${entry.path}' is missing on the drive")
                actual.size != entry.size ->
                    problems.add("'/${entry.path}' is ${actual.size} bytes on the drive but ${entry.size} in the image")
                entry.size == 0L -> checked++
                else -> {
                    val bad = firstMismatch(
                        entry.size, fullCheck,
                        { off, len -> entry.readAt(source, off, len) },
                        { off, len -> actual.readAt(deviceReader, off, len) }
                    )
                    if (bad >= 0) {
                        val lba = (actual.offset + bad) / device.blockSize
                        FlashReport.mismatch("/${entry.path}", lba, bad, "image byte", "drive byte")
                        problems.add("'/${entry.path}' differs from the image at byte $bad (about LBA $lba)")
                    } else checked++
                }
            }
        }

        if (actualByPath.keys.none { it == ".disk/info" } && expected.any { it.path.equals(".disk/info", true) }) {
            problems.add("/.disk/info is not reachable on the drive - GRUB will fail with 'no such device'")
        }
        progress(100, if (fullCheck) "Full verification finished" else "Boot content check finished")
        if (problems.isNotEmpty()) Log.e(TAG, "Raw clone content problems: $problems")
        return Report(problems.isEmpty(), checked, problems, notes)
    }

    /** Verifies the copied file tree inside the FAT32 boot partition. */
    fun verifyFileCopy(
        device: UsbBulkStorageDevice,
        partitionStartLba: Long,
        source: IsoSource,
        sourceEntries: List<IsoEntry>,
        fullCheck: Boolean,
        isCancelled: () -> Boolean,
        /**
         * Image paths that deliberately do not exist on the drive - currently the
         * oversized `sources/install.wim` that was rewritten as a spanned
         * `install.swm` set so it fits on FAT32. Those parts are checked by
         * [WindowsMediaVerifier] instead.
         */
        skipPaths: Set<String> = emptySet(),
        progress: (Int, String) -> Unit
    ): Report {
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()

        val skip = skipPaths.map { it.trim('/').lowercase() }.toHashSet()
        // Use the exact UDF/ISO9660 tree selected by ImageDirectory and used by
        // BootableWriter. Re-parsing ISO9660 here produced false failures on
        // Windows media whose UDF tree contains different names or 64-bit sizes.
        val allEntries = sourceEntries
            .filter { !it.isDirectory && !skip.contains(it.path.trim('/').lowercase()) }
        val expected = if (fullCheck) allEntries else selectCriticalFiles(allEntries)
        
        val fat = runCatching { Fat32Reader(device, partitionStartLba) }.getOrElse {
            return Report(false, 0, listOf("Could not re-read the boot partition: ${it.message}"), notes)
        }

        var checked = 0
        val deadline = if (fullCheck) Long.MAX_VALUE else (System.currentTimeMillis() + TIME_BUDGET_MS)
        expected.forEachIndexed { index, entry ->
            if (isCancelled()) return Report(false, checked, listOf("Cancelled"), notes)
            progress((index * 100) / expected.size.coerceAtLeast(1), "Checking /${entry.path}")
            if (!fullCheck && System.currentTimeMillis() > deadline) {
                notes.add("Skipped ${expected.size - index} spot checks to keep the flash responsive")
                return@forEachIndexed
            }
            val found = runCatching { fat.find(entry.path) }.getOrNull()
            when {
                found == null -> problems.add("'/${entry.path}' was not copied to the drive")
                found.size != entry.size ->
                    problems.add("'/${entry.path}' is ${found.size} bytes on the drive but ${entry.size} in the image")
                entry.size == 0L -> checked++
                else -> {
                    val bad = firstMismatch(
                        entry.size, fullCheck,
                        { off, len -> entry.readAt(source, off, len) },
                        { off, len -> fat.read(found, off, len) }
                    )
                    if (bad >= 0) {
                        FlashReport.mismatch(
                            "/${entry.path}", partitionStartLba + bad / device.blockSize, bad,
                            "image byte", "drive byte"
                        )
                        problems.add("'/${entry.path}' differs from the image at byte $bad (partition byte offset $bad)")
                    } else checked++
                }
            }
        }

        if (expected.none { it.path.lowercase().startsWith("efi/boot/") }) {
            notes.add("This image ships no EFI/BOOT loader, so UEFI firmware has nothing to start")
        }
        progress(100, if (fullCheck) "Full verification finished" else "Boot content check finished")
        if (problems.isNotEmpty()) Log.e(TAG, "File copy content problems: $problems")
        return Report(problems.isEmpty(), checked, problems, notes)
    }

    /**
     * Compares files between source and destination and returns the offset of
     * the first differing byte, or -1 when they match. Reporting the exact
     * offset is what lets the UI name the failing LBA instead of just saying
     * "verification failed".
     */
    private fun firstMismatch(
        size: Long,
        full: Boolean,
        readSource: (Long, Int) -> ByteArray,
        readDevice: (Long, Int) -> ByteArray
    ): Long {
        val windows = if (full) {
            val out = mutableListOf<Pair<Long, Int>>()
            var offset = 0L
            while (offset < size) {
                val len = minOf(256L * 1024, size - offset).toInt()
                out.add(offset to len)
                offset += len
            }
            out
        } else {
            val out = mutableListOf<Pair<Long, Int>>()
            val head = minOf(size, SAMPLE_BYTES.toLong()).toInt()
            out.add(0L to head)
            if (size > SAMPLE_BYTES * 2L) {
                out.add((size / 2) to SAMPLE_BYTES)
                out.add((size - SAMPLE_BYTES) to SAMPLE_BYTES)
            } else if (size > SAMPLE_BYTES) {
                out.add(head.toLong() to (size - head).toInt())
            }
            out
        }
        windows.forEach { (offset, length) ->
            if (length <= 0) return@forEach
            val expected = readSource(offset, length)
            val actual = readDevice(offset, length)
            val limit = minOf(length, expected.size, actual.size)
            for (i in 0 until limit) {
                if (expected[i] != actual[i]) return offset + i
            }
            if (actual.size < length) return offset + actual.size
        }
        return -1L
    }

    /**
     * Compares files between source and destination. If [full] is true, hashes
     * the entire file. If false, only samples the head, middle and tail.
     */
    private fun compareFiles(
        size: Long,
        full: Boolean,
        readSource: (Long, Int) -> ByteArray,
        readDevice: (Long, Int) -> ByteArray
    ): Boolean {
        if (full) {
            val digestSource = MessageDigest.getInstance("SHA-256")
            val digestDevice = MessageDigest.getInstance("SHA-256")
            val bufferSize = 256 * 1024
            var offset = 0L
            while (offset < size) {
                val len = minOf(bufferSize.toLong(), size - offset).toInt()
                digestSource.update(readSource(offset, len))
                digestDevice.update(readDevice(offset, len))
                offset += len
            }
            return digestSource.digest().contentEquals(digestDevice.digest())
        }

        val windows = mutableListOf<Pair<Long, Int>>()
        val head = minOf(size, SAMPLE_BYTES.toLong()).toInt()
        windows.add(0L to head)
        if (size > SAMPLE_BYTES * 2L) {
            windows.add((size / 2) to SAMPLE_BYTES)
            windows.add((size - SAMPLE_BYTES) to SAMPLE_BYTES)
        } else if (size > SAMPLE_BYTES) {
            windows.add(head.toLong() to (size - head).toInt())
        }

        val a = MessageDigest.getInstance("SHA-256")
        val b = MessageDigest.getInstance("SHA-256")
        windows.forEach { (offset, length) ->
            if (length <= 0) return@forEach
            a.update(readSource(offset, length), 0, length)
            val actual = readDevice(offset, length)
            b.update(actual, 0, minOf(length, actual.size))
        }
        return a.digest().contentEquals(b.digest())
    }

    /** One UEFI loader that was expected on the finished drive. */
    data class EfiResult(val path: String, val present: Boolean, val sizeOk: Boolean, val sha256: String)

    /**
     * UEFI firmware only ever starts `\EFI\BOOT\BOOT*.EFI` from the first FAT
     * volume it finds, so after flashing we re-read exactly those files off the
     * drive, hash them in full and compare with the image. The hashes go into
     * the exportable report.
     *
     * [readOnDrive] returns (sizeOnDrive, reader) for a path, or null if it is
     * not reachable on the finished drive.
     */
    fun verifyEfiLoaders(
        source: IsoSource,
        entries: List<IsoEntry>,
        readOnDrive: (String) -> Pair<Long, (Long, Int) -> ByteArray>?,
        progress: (Int, String) -> Unit
    ): Pair<Report, List<EfiResult>> {
        val expected = entries.filter {
            !it.isDirectory && it.path.lowercase().startsWith("efi/boot/") &&
                it.path.lowercase().endsWith(".efi")
        }
        if (expected.isEmpty()) {
            return Report(true, 0, emptyList(), listOf("The image ships no EFI/BOOT loader, UEFI check skipped")) to emptyList()
        }

        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val results = mutableListOf<EfiResult>()
        var checked = 0

        expected.forEachIndexed { index, entry ->
            progress((index * 100) / expected.size, "Verifying UEFI loader /${entry.path}")
            val onDrive = runCatching { readOnDrive(entry.path) }.getOrNull()
            if (onDrive == null) {
                problems.add("'/${entry.path}' is missing on the drive - UEFI firmware will not find a bootloader")
                results.add(EfiResult(entry.path, present = false, sizeOk = false, sha256 = ""))
                return@forEachIndexed
            }
            val (size, read) = onDrive
            if (size != entry.size) {
                problems.add("'/${entry.path}' is $size bytes on the drive but ${entry.size} in the image")
                results.add(EfiResult(entry.path, present = true, sizeOk = false, sha256 = ""))
                return@forEachIndexed
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            var mismatchAt = -1L
            while (offset < size) {
                val len = minOf(256L * 1024, size - offset).toInt()
                val a = entry.readAt(source, offset, len)
                val b = read(offset, len)
                sourceDigest.update(a, 0, len)
                digest.update(b, 0, minOf(len, b.size))
                if (mismatchAt < 0) {
                    for (i in 0 until minOf(len, b.size)) {
                        if (a[i] != b[i]) { mismatchAt = offset + i; break }
                    }
                }
                offset += len
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            FlashReport.checksum("drive:/${entry.path}", sha)
            val same = sha == sourceDigest.digest().joinToString("") { "%02x".format(it) }
            if (!same) {
                FlashReport.mismatch("/${entry.path}", 0, maxOf(mismatchAt, 0), "image byte", "drive byte")
                problems.add("'/${entry.path}' does not match the image (first difference at byte ${maxOf(mismatchAt, 0)})")
            } else {
                checked++
                notes.add("/${entry.path} verified, SHA-256 $sha")
            }
            results.add(EfiResult(entry.path, present = true, sizeOk = true, sha256 = sha))
        }

        progress(100, "UEFI loader check finished")
        if (problems.isNotEmpty()) Log.e(TAG, "UEFI loader problems: $problems")
        return Report(problems.isEmpty(), checked, problems, notes) to results
    }
}
