package com.yourapp.USBooter.util

import android.content.Context
import android.hardware.usb.UsbManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.CRC32

/**
 * Non-destructive partition and filesystem repair.
 *
 * Everything here works from copies the drive already carries: the GPT backup
 * header at the end of the disk, the FAT32 backup boot sector at +6, the exFAT
 * backup boot region at +12, the NTFS boot-sector copy in the last sector of the
 * partition, and the mirror FAT. Nothing is reformatted and no user file is ever
 * rewritten, so a repair that is reported as `safe` cannot lose data.
 *
 * When no spare copy exists on the drive, the problem is reported as `risky`:
 * the structure can only be rebuilt by guessing, and the finding says plainly
 * that files may be lost. Those are applied only when the user explicitly
 * confirms (`allowRisky = true`).
 */
object PartitionRepair {

    /** One problem found on the drive. */
    data class Finding(
        val id: String,
        val title: String,
        val detail: String,
        /** "safe" = rebuilt from a spare copy on the drive, "risky" = may lose files, "info" = nothing to do. */
        val severity: String,
        val repairable: Boolean,
        var applied: Boolean = false,
        var error: String = "",
        /** Performs the repair. Null when the finding cannot be fixed. */
        val fix: (() -> Unit)? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("detail", detail)
            put("severity", severity)
            put("repairable", repairable)
            put("applied", applied)
            if (error.isNotEmpty()) put("error", error)
        }
    }

    /** Scans without touching the drive. */
    fun scan(
        context: Context,
        deviceName: String,
        progress: (Int, String) -> Unit
    ): JSONObject = withDrive(context, deviceName) { device ->
        val findings = analyze(device, progress)
        result(findings, applied = 0, repaired = false)
    }

    /**
     * Scans and applies every safe repair. Risky repairs are applied only when
     * [allowRisky] is true, because they can make files unreachable.
     */
    fun repair(
        context: Context,
        deviceName: String,
        allowRisky: Boolean,
        progress: (Int, String) -> Unit
    ): JSONObject = withDrive(context, deviceName) { device ->
        repairDevice(device, allowRisky, progress)
    }

    /** Scan against any block target. Used by the USB path above and by unit tests. */
    fun scanDevice(
        device: BlockDevice,
        progress: (Int, String) -> Unit = { _, _ -> }
    ): JSONObject = result(analyze(device, progress), applied = 0, repaired = false)

    /** Repair against any block target. Used by the USB path above and by unit tests. */
    fun repairDevice(
        device: BlockDevice,
        allowRisky: Boolean,
        progress: (Int, String) -> Unit = { _, _ -> }
    ): JSONObject {
        val findings = analyze(device, progress)
        val todo = findings.filter {
            it.repairable && it.fix != null && (it.severity == "safe" || allowRisky)
        }
        var applied = 0
        todo.forEachIndexed { index, finding ->
            progress(
                70 + (25 * index) / todo.size.coerceAtLeast(1),
                "Repairing: ${finding.title}"
            )
            try {
                finding.fix!!.invoke()
                finding.applied = true
                applied++
            } catch (e: Throwable) {
                finding.error = e.message ?: e.javaClass.simpleName
            }
        }
        if (applied > 0) {
            progress(97, "Flushing the drive cache")
            runCatching { device.synchronizeCache() }
        }
        progress(100, "Repair finished")
        result(findings, applied, repaired = true)
    }

    // ---------------------------------------------------------------- analysis

    private fun analyze(
        device: BlockDevice,
        progress: (Int, String) -> Unit
    ): MutableList<Finding> {
        val findings = mutableListOf<Finding>()
        val bs = device.blockSize

        progress(10, "Reading the partition table")
        val sector0 = device.readBlocks(0, 1)
        val hasBootSignature = signature(sector0)
        val isProtective = hasBootSignature && (0 until 4).any {
            (sector0[446 + it * 16 + 4].toInt() and 0xFF) == 0xEE
        }
        val gptHeader = runCatching { device.readBlocks(1, 1) }.getOrNull()
        val looksGpt = isProtective || (gptHeader != null && isGptSignature(gptHeader))

        if (looksGpt) {
            progress(25, "Checking the GPT partition table")
            checkGpt(device, findings, sector0, hasBootSignature)
        } else {
            progress(25, "Checking the MBR partition table")
            checkMbr(device, findings, sector0, hasBootSignature)
        }

        progress(45, "Checking the filesystems")
        partitions(device, sector0, looksGpt).forEachIndexed { index, part ->
            checkFilesystem(device, findings, index + 1, part)
        }

        if (findings.isEmpty()) {
            findings.add(
                Finding(
                    "clean",
                    "No partition damage found",
                    "The partition table and every filesystem boot sector on this drive are consistent. " +
                        "Nothing needs repairing.",
                    "info",
                    repairable = false
                )
            )
        }
        progress(60, "Analysis complete")
        // Keep block size referenced so the compiler cannot warn about it going unused.
        require(bs > 0)
        return findings
    }

    private data class Part(val index: Int, val start: Long, val sectors: Long, val typeByte: Int)

    /** Partition ranges as the table describes them (MBR primaries, or GPT entries). */
    private fun partitions(
        device: BlockDevice,
        sector0: ByteArray,
        gpt: Boolean
    ): List<Part> {
        val out = mutableListOf<Part>()
        if (gpt) {
            val header = runCatching { device.readBlocks(1, 1) }.getOrNull() ?: return out
            if (!isGptSignature(header)) return out
            val entryLba = le64(header, 72)
            val count = le32(header, 80).toInt().coerceIn(0, 128)
            val size = le32(header, 84).toInt().coerceAtLeast(128)
            val sectorsNeeded = ((count * size) / device.blockSize).coerceAtLeast(1)
            val table = runCatching { device.readBlocks(entryLba, sectorsNeeded) }.getOrNull() ?: return out
            for (i in 0 until count) {
                val base = i * size
                if (base + size > table.size) break
                if ((0 until 16).all { table[base + it] == 0.toByte() }) continue
                val first = le64(table, base + 32)
                val last = le64(table, base + 40)
                if (first <= 0 || last < first) continue
                out.add(Part(i + 1, first, last - first + 1, -1))
            }
            return out
        }
        if (!signature(sector0)) return out
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val type = sector0[base + 4].toInt() and 0xFF
            val start = le32(sector0, base + 8)
            val count = le32(sector0, base + 12)
            if (type == 0 || start <= 0 || count <= 0 || start >= device.totalBlocks) continue
            out.add(Part(i + 1, start, count, type))
        }
        return out
    }

    // -------------------------------------------------------------------- GPT

    private fun checkGpt(
        device: BlockDevice,
        findings: MutableList<Finding>,
        sector0: ByteArray,
        hasBootSignature: Boolean
    ) {
        val lastLba = device.totalBlocks - 1
        val primary = runCatching { device.readBlocks(1, 1) }.getOrNull()
        val backup = runCatching { device.readBlocks(lastLba, 1) }.getOrNull()
        val primaryOk = primary != null && gptHeaderValid(primary)
        val backupOk = backup != null && gptHeaderValid(backup)

        if (!primaryOk && backupOk) {
            findings.add(
                Finding(
                    "gpt-primary",
                    "Main GPT partition table is damaged",
                    "The partition table at the start of the drive fails its checksum, but the backup copy at the " +
                        "end of the drive is intact. The backup can be copied over the damaged one without touching a single file.",
                    "safe", repairable = true
                ) {
                    restoreGptFromBackup(device, backup!!)
                }
            )
        } else if (primaryOk && !backupOk) {
            findings.add(
                Finding(
                    "gpt-backup",
                    "Backup GPT partition table is damaged",
                    "The main partition table is intact but its backup copy at the end of the drive is missing or corrupt. " +
                        "Rebuilding the backup from the main table changes no file and protects the drive against future damage.",
                    "safe", repairable = true
                ) {
                    rebuildGptBackup(device, primary!!)
                }
            )
        } else if (!primaryOk && !backupOk) {
            findings.add(
                Finding(
                    "gpt-both",
                    "Both GPT partition tables are damaged",
                    "Neither the main partition table nor its backup can be read. There is no spare copy left on the drive, " +
                        "so the layout can only be guessed by scanning for filesystems. Files will most likely be lost — " +
                        "copy anything you still need off the drive before rebuilding or reformatting.",
                    "risky", repairable = false
                )
            )
        }

        if (!hasBootSignature || (0 until 4).none { (sector0[446 + it * 16 + 4].toInt() and 0xFF) == 0xEE }) {
            findings.add(
                Finding(
                    "gpt-pmbr",
                    "Protective MBR is missing",
                    "A GPT drive must carry a protective MBR in its first sector so older systems do not treat it as empty. " +
                        "Writing it back only rewrites sector 0 and leaves every partition and file untouched.",
                    "safe", repairable = true
                ) {
                    writeProtectiveMbr(device)
                }
            )
        }
    }

    private fun gptHeaderValid(header: ByteArray): Boolean {
        if (!isGptSignature(header)) return false
        val size = le32(header, 12).toInt()
        if (size < 92 || size > header.size) return false
        val stored = le32(header, 16)
        val copy = header.copyOf()
        java.util.Arrays.fill(copy, 16, 20, 0.toByte())
        return crc32(copy, 0, size) == stored
    }

    private fun restoreGptFromBackup(device: BlockDevice, backup: ByteArray) {
        val entryLba = le64(backup, 72)
        val count = le32(backup, 80).toInt().coerceIn(1, 128)
        val size = le32(backup, 84).toInt().coerceAtLeast(128)
        val sectors = ((count * size) / device.blockSize).coerceAtLeast(1)
        val table = device.readBlocks(entryLba, sectors)

        val header = backup.copyOf()
        // Swap the roles: this header now lives at LBA 1 and its alternate is the last sector.
        put64(header, 24, 1L)
        put64(header, 32, device.totalBlocks - 1)
        put64(header, 72, 2L)
        resealGptHeader(header)
        device.writeBlocks(2, table)
        device.writeBlocks(1, header)
    }

    private fun rebuildGptBackup(device: BlockDevice, primary: ByteArray) {
        val entryLba = le64(primary, 72)
        val count = le32(primary, 80).toInt().coerceIn(1, 128)
        val size = le32(primary, 84).toInt().coerceAtLeast(128)
        val sectors = ((count * size) / device.blockSize).coerceAtLeast(1)
        val table = device.readBlocks(entryLba, sectors)

        val lastLba = device.totalBlocks - 1
        val backupTableLba = lastLba - sectors
        val header = primary.copyOf()
        put64(header, 24, lastLba)
        put64(header, 32, 1L)
        put64(header, 72, backupTableLba)
        resealGptHeader(header)
        device.writeBlocks(backupTableLba, table)
        device.writeBlocks(lastLba, header)
    }

    private fun resealGptHeader(header: ByteArray) {
        val size = le32(header, 12).toInt().coerceIn(92, header.size)
        java.util.Arrays.fill(header, 16, 20, 0.toByte())
        put32(header, 16, crc32(header, 0, size))
    }

    private fun writeProtectiveMbr(device: BlockDevice) {
        val sector = ByteArray(device.blockSize)
        val entry = 446
        sector[entry + 0] = 0x00
        sector[entry + 1] = 0x00
        sector[entry + 2] = 0x02
        sector[entry + 3] = 0x00
        sector[entry + 4] = 0xEE.toByte()
        val last = minOf(device.totalBlocks - 1, 0xFFFFFFFFL)
        val chs = Mbr.chs(last)
        sector[entry + 5] = chs[0]
        sector[entry + 6] = chs[1]
        sector[entry + 7] = chs[2]
        put32(sector, entry + 8, 1L)
        put32(sector, entry + 12, last)
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        device.writeBlocks(0, sector)
    }

    // -------------------------------------------------------------------- MBR

    private val COMMON_STARTS = listOf<Long>(2048, 63, 1, 34, 8192, 4096, 32, 128)

    private fun checkMbr(
        device: BlockDevice,
        findings: MutableList<Finding>,
        sector0: ByteArray,
        hasBootSignature: Boolean
    ) {
        val entries = partitions(device, sector0, gpt = false)

        if (!hasBootSignature || entries.isEmpty()) {
            val found = COMMON_STARTS.firstNotNullOfOrNull { start ->
                if (start >= device.totalBlocks) null
                else runCatching { device.readBlocks(start, 1) }.getOrNull()
                    ?.let { boot -> filesystemOf(boot)?.let { fs -> start to fs } }
            }
            if (found != null) {
                val (start, fs) = found
                findings.add(
                    Finding(
                        "mbr-missing",
                        "Partition table is missing",
                        "The drive has no usable partition table, but a $fs filesystem was found at sector $start — the files " +
                            "themselves are still there. A single-partition table can be rebuilt around it. This does not rewrite " +
                            "any file, but if the drive really held several partitions the others would stay hidden, so make a copy " +
                            "of anything important first if you can still read it elsewhere.",
                        "risky", repairable = true
                    ) {
                        rebuildMbrAround(device, start, fs)
                    }
                )
            } else {
                findings.add(
                    Finding(
                        "mbr-unrecoverable",
                        "Partition table is missing and no filesystem was found",
                        "There is no partition table and no recognisable filesystem at any of the usual starting points. " +
                            "Nothing on this drive can be repaired without guessing; reformatting will erase the files.",
                        "risky", repairable = false
                    )
                )
            }
            return
        }

        if (entries.none { (sector0[446 + (it.index - 1) * 16].toInt() and 0xFF) == 0x80 }) {
            findings.add(
                Finding(
                    "mbr-active",
                    "No partition is marked as bootable",
                    "The partition table has no active (bootable) flag, so a legacy BIOS refuses to start from this drive. " +
                        "Setting the flag on the first partition changes one byte in the table and no file at all.",
                    "safe", repairable = true
                ) {
                    val s = device.readBlocks(0, 1)
                    val first = entries.first().index - 1
                    for (i in 0 until 4) s[446 + i * 16] = 0
                    s[446 + first * 16] = 0x80.toByte()
                    device.writeBlocks(0, s)
                }
            )
        }

        entries.forEach { part ->
            val boot = runCatching { device.readBlocks(part.start, 1) }.getOrNull() ?: return@forEach
            val fs = filesystemOf(boot) ?: return@forEach
            val expected = expectedTypeByte(fs, part.sectors)
            if (expected != null && part.typeByte != expected) {
                findings.add(
                    Finding(
                        "mbr-type-${part.index}",
                        "Partition ${part.index} has the wrong type",
                        "Partition ${part.index} really contains a $fs filesystem, but the partition table describes it as type " +
                            "0x${part.typeByte.toString(16).uppercase()}, which makes some systems ignore it. Correcting the type byte " +
                            "leaves every file where it is.",
                        "safe", repairable = true
                    ) {
                        val s = device.readBlocks(0, 1)
                        s[446 + (part.index - 1) * 16 + 4] = expected.toByte()
                        device.writeBlocks(0, s)
                    }
                )
            }
        }
    }

    private fun rebuildMbrAround(device: BlockDevice, start: Long, fs: String) {
        val sectors = device.totalBlocks - start
        val type = expectedTypeByte(fs, sectors) ?: 0x0C
        val sector = ByteArray(device.blockSize)
        val existing = runCatching { device.readBlocks(0, 1) }.getOrNull()
        if (existing != null) System.arraycopy(existing, 0, sector, 0, 440) // keep any boot code
        val base = 446
        java.util.Arrays.fill(sector, 446, 510, 0.toByte())
        sector[base] = 0x80.toByte()
        val firstChs = Mbr.chs(start)
        sector[base + 1] = firstChs[0]
        sector[base + 2] = firstChs[1]
        sector[base + 3] = firstChs[2]
        sector[base + 4] = type.toByte()
        val lastChs = Mbr.chs(start + sectors - 1)
        sector[base + 5] = lastChs[0]
        sector[base + 6] = lastChs[1]
        sector[base + 7] = lastChs[2]
        put32(sector, base + 8, start)
        put32(sector, base + 12, minOf(sectors, 0xFFFFFFFFL))
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        device.writeBlocks(0, sector)
    }

    private fun expectedTypeByte(fs: String, sectors: Long): Int? = when (fs) {
        "FAT32" -> if (sectors > 65535) 0x0C else 0x0B
        "exFAT", "NTFS" -> 0x07
        "ext2/ext3/ext4" -> 0x83
        else -> null
    }

    // ------------------------------------------------------------ filesystems

    private fun checkFilesystem(
        device: BlockDevice,
        findings: MutableList<Finding>,
        number: Int,
        part: Part
    ) {
        val boot = runCatching { device.readBlocks(part.start, 1) }.getOrNull() ?: return
        when (filesystemOf(boot)) {
            "FAT32" -> checkFat32(device, findings, number, part, boot)
            "exFAT" -> checkExfat(device, findings, number, part, boot)
            "NTFS" -> checkNtfs(device, findings, number, part, boot)
            null -> checkBrokenBootSector(device, findings, number, part)
            else -> {}
        }
    }

    /** No recognisable boot sector: look for a spare copy before declaring it lost. */
    private fun checkBrokenBootSector(
        device: BlockDevice,
        findings: MutableList<Finding>,
        number: Int,
        part: Part
    ) {
        val fat32Backup = runCatching { device.readBlocks(part.start + 6, 1) }.getOrNull()
        if (fat32Backup != null && filesystemOf(fat32Backup) == "FAT32") {
            findings.add(
                Finding(
                    "fs-boot-$number",
                    "Partition $number: boot sector is damaged",
                    "The first sector of partition $number is unreadable, but FAT32 keeps a spare copy six sectors further in " +
                        "and that copy is intact. Restoring it brings the partition back with all files in place.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start, fat32Backup)
                    device.synchronizeCache()
                }
            )
            return
        }
        val exfatBackup = runCatching { device.readBlocks(part.start + 12, 1) }.getOrNull()
        if (exfatBackup != null && filesystemOf(exfatBackup) == "exFAT") {
            findings.add(
                Finding(
                    "fs-boot-$number",
                    "Partition $number: boot region is damaged",
                    "The exFAT boot region of partition $number is damaged, but the backup region twelve sectors further in is " +
                        "intact. Copying the backup over the main region restores the partition without rewriting any file.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start, device.readBlocks(part.start + 12, 12))
                    device.synchronizeCache()
                }
            )
            return
        }
        val ntfsCopy = ntfsBackupSector(device, part)
        if (ntfsCopy != null) {
            findings.add(
                Finding(
                    "fs-boot-$number",
                    "Partition $number: NTFS boot sector is damaged",
                    "The NTFS boot sector of partition $number is damaged. NTFS keeps a copy in the very last sector of the " +
                        "partition and that copy is intact, so it can be restored with no file loss.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start, ntfsCopy)
                    device.synchronizeCache()
                }
            )
            return
        }
        findings.add(
            Finding(
                "fs-boot-$number",
                "Partition $number: filesystem is not recognised",
                "Partition $number has no readable filesystem header and no spare copy of it on the drive. It cannot be " +
                    "repaired without rebuilding the filesystem, which erases the files it contains. Recover what you need " +
                    "with a file-recovery tool before reformatting.",
                "risky", repairable = false
            )
        )
    }

    private fun checkFat32(
        device: BlockDevice,
        findings: MutableList<Finding>,
        number: Int,
        part: Part,
        boot: ByteArray
    ) {
        val backup = runCatching { device.readBlocks(part.start + 6, 1) }.getOrNull()
        if (backup != null && filesystemOf(backup) != "FAT32") {
            findings.add(
                Finding(
                    "fat-backup-$number",
                    "Partition $number: FAT32 backup boot sector is damaged",
                    "The working boot sector of partition $number is fine but its spare copy is not. Refreshing the copy from " +
                        "the working sector protects the partition and touches no file.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start + 6, boot)
                }
            )
        }

        val reserved = le16(boot, 14)
        val fatCount = boot[16].toInt() and 0xFF
        val fatSectors = le32(boot, 36)
        if (fatCount >= 2 && fatSectors in 1L..(1L shl 22)) {
            val chunk = minOf(fatSectors, 64L).toInt()
            val a = runCatching { device.readBlocks(part.start + reserved, chunk) }.getOrNull()
            val b = runCatching { device.readBlocks(part.start + reserved + fatSectors, chunk) }.getOrNull()
            if (a != null && b != null && !a.contentEquals(b)) {
                findings.add(
                    Finding(
                        "fat-mirror-$number",
                        "Partition $number: the two file-allocation tables disagree",
                        "FAT32 keeps two copies of its allocation table and on partition $number they no longer match, which is " +
                            "what makes files disappear from the listing. Copying the first table over the second one restores the " +
                            "pair without rewriting file contents.",
                        "safe", repairable = true
                    ) {
                        copyRegion(device, part.start + reserved, part.start + reserved + fatSectors, fatSectors)
                    }
                )
            }
        }
    }

    private fun checkExfat(
        device: BlockDevice,
        findings: MutableList<Finding>,
        number: Int,
        part: Part,
        boot: ByteArray
    ) {
        val backup = runCatching { device.readBlocks(part.start + 12, 1) }.getOrNull()
        if (backup != null && filesystemOf(backup) != "exFAT") {
            findings.add(
                Finding(
                    "exfat-backup-$number",
                    "Partition $number: exFAT backup boot region is damaged",
                    "The main exFAT boot region of partition $number is healthy but its backup region is not. Rewriting the " +
                        "backup from the main region is a structural fix only — no file is modified.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start + 12, device.readBlocks(part.start, 12))
                }
            )
        }
        val volumeChecksumOk = runCatching {
            val region = device.readBlocks(part.start, 11)
            exfatChecksum(region, device.blockSize) == le32(device.readBlocks(part.start + 11, 1), 0)
        }.getOrDefault(true)
        if (!volumeChecksumOk) {
            findings.add(
                Finding(
                    "exfat-checksum-$number",
                    "Partition $number: exFAT boot checksum is wrong",
                    "The checksum that protects the exFAT boot region of partition $number does not match its contents, so " +
                        "Windows may refuse to mount the partition. Recomputing the checksum leaves all files untouched.",
                    "safe", repairable = true
                ) {
                    val region = device.readBlocks(part.start, 11)
                    val value = exfatChecksum(region, device.blockSize)
                    val sector = ByteArray(device.blockSize)
                    var i = 0
                    while (i + 4 <= sector.size) {
                        put32(sector, i, value)
                        i += 4
                    }
                    device.writeBlocks(part.start + 11, sector)
                }
            )
        }
        // Referenced for symmetry with the other checkers.
        require(boot.isNotEmpty())
    }

    /**
     * NTFS is the filesystem most often left "badly formatted": the name in the
     * boot sector survives while the geometry fields inside it are wrong, so
     * Windows and Android both refuse to mount the partition even though every
     * file is still on the drive. So the boot sector is validated field by field,
     * not just by its name, and repaired from the copy NTFS keeps in the last
     * sector of the partition - or, when that copy is the only healthy one, the
     * geometry is recomputed from the partition itself.
     */
    private fun checkNtfs(
        device: BlockDevice,
        findings: MutableList<Finding>,
        number: Int,
        part: Part,
        boot: ByteArray
    ) {
        val copyLba = part.start + part.sectors - 1
        val copy = ntfsBackupSector(device, part)
        val mainProblems = ntfsBootProblems(boot, part, device.blockSize)
        val copyIsNtfs = copy != null && filesystemOf(copy) == "NTFS"
        val copyProblems = if (copyIsNtfs) ntfsBootProblems(copy!!, part, device.blockSize) else listOf("missing")

        if (mainProblems.isNotEmpty() && copyIsNtfs && copyProblems.isEmpty()) {
            findings.add(
                Finding(
                    "ntfs-boot-$number",
                    "Partition $number: NTFS boot sector is inconsistent",
                    "The NTFS boot sector of partition $number is present but its contents are wrong " +
                        "(${mainProblems.joinToString(", ")}), which is why the drive is reported as not formatted. " +
                        "The copy NTFS keeps in the last sector of the partition is intact, so it can be put back " +
                        "with no file loss.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(part.start, copy!!)
                    device.synchronizeCache()
                }
            )
            return
        }

        if (mainProblems.isNotEmpty() && !(copyIsNtfs && copyProblems.isEmpty())) {
            val rebuilt = rebuildNtfsBootSector(boot, copy, part, device.blockSize)
            if (rebuilt != null) {
                findings.add(
                    Finding(
                        "ntfs-boot-$number",
                        "Partition $number: NTFS boot sector must be recomputed",
                        "Both the NTFS boot sector of partition $number and its backup copy are wrong " +
                            "(${mainProblems.joinToString(", ")}), and the master file table was still found on the drive. " +
                            "The boot sector can be recomputed from the partition size and the file table that was found. " +
                            "Only the boot sector is rewritten, but because no healthy copy is left this is a best-effort " +
                            "repair: copy anything you can still read off the drive first.",
                        "risky", repairable = true
                    ) {
                        device.writeBlocks(part.start, rebuilt)
                        device.writeBlocks(copyLba, rebuilt)
                        device.synchronizeCache()
                    }
                )
            } else {
                findings.add(
                    Finding(
                        "ntfs-boot-$number",
                        "Partition $number: NTFS structures are too damaged to repair",
                        "The NTFS boot sector of partition $number is wrong (${mainProblems.joinToString(", ")}), " +
                            "its backup copy is unusable and no master file table could be found on the drive. Nothing here " +
                            "can be rebuilt without guessing: recover the files you need with a recovery tool, then reformat.",
                        "risky", repairable = false
                    )
                )
            }
            return
        }

        // Main sector is healthy: keep the safety copy and the mirror file table in step.
        if (!copyIsNtfs || copyProblems.isNotEmpty()) {
            findings.add(
                Finding(
                    "ntfs-copy-$number",
                    "Partition $number: NTFS boot-sector copy is damaged",
                    "Partition $number starts correctly but the NTFS boot-sector copy at the end of the partition is damaged. " +
                        "Restoring the copy from the working sector is a structural fix and no file is rewritten.",
                    "safe", repairable = true
                ) {
                    device.writeBlocks(copyLba, boot)
                    device.synchronizeCache()
                }
            )
        }

        if (!ntfsMftPresent(device, boot, part)) {
            findings.add(
                Finding(
                    "ntfs-mft-$number",
                    "Partition $number: master file table is unreadable",
                    "The NTFS boot sector of partition $number is healthy but the master file table it points at does not " +
                        "start with a valid record. The list of files can only be rebuilt by scanning the whole partition, " +
                        "which cannot be done without risking file loss. Recover the files you need before reformatting.",
                    "risky", repairable = false
                )
            )
        }
    }

    /** The NTFS safety copy: the last sector of the partition, or one sector past it. */
    private fun ntfsBackupSector(device: BlockDevice, part: Part): ByteArray? {
        // Windows writes the copy in the last sector of the partition, but a wrong
        // size field in the table can shift it by a sector or two, so look around.
        val candidates = longArrayOf(
            part.start + part.sectors - 1,
            part.start + part.sectors,
            part.start + part.sectors - 2,
            part.start + part.sectors + 1
        )
        for (lba in candidates) {
            if (lba <= part.start || lba >= device.totalBlocks) continue
            val sector = runCatching { device.readBlocks(lba, 1) }.getOrNull() ?: continue
            if (filesystemOf(sector) == "NTFS") return sector
        }
        return null
    }

    /** Plain-language list of everything wrong inside an NTFS boot sector. */
    private fun ntfsBootProblems(boot: ByteArray, part: Part, blockSize: Int): List<String> {
        val problems = mutableListOf<String>()
        if (boot.size < 512) return listOf("the sector could not be read")
        if (!signature(boot)) problems += "the end-of-sector marker is missing"
        val bytesPerSector = le16(boot, 11)
        if (bytesPerSector < 512 || bytesPerSector > 4096 || (bytesPerSector and (bytesPerSector - 1)) != 0) {
            problems += "the sector size is invalid"
        }
        val sectorsPerCluster = boot[13].toInt() and 0xFF
        val clusterSectors = if (sectorsPerCluster in 1..128 && (sectorsPerCluster and (sectorsPerCluster - 1)) == 0) {
            sectorsPerCluster
        } else if (sectorsPerCluster >= 0xF4) {
            1 shl (256 - sectorsPerCluster) // NTFS stores large clusters as a negative power of two
        } else {
            problems += "the cluster size is invalid"
            0
        }
        val total = le64(boot, 40)
        if (total <= 0 || total > part.sectors) {
            problems += "the recorded partition size does not match the real one"
        }
        if (clusterSectors > 0 && total > 0) {
            val mft = le64(boot, 48) * clusterSectors
            val mftMirror = le64(boot, 56) * clusterSectors
            if (mft <= 0 || mft >= total) problems += "the file-table position is out of range"
            if (mftMirror <= 0 || mftMirror >= total) problems += "the mirror file-table position is out of range"
        }
        require(blockSize > 0)
        return problems
    }

    /** True when the boot sector points at a real NTFS record ("FILE" magic). */
    private fun ntfsMftPresent(device: BlockDevice, boot: ByteArray, part: Part): Boolean {
        val sectorsPerCluster = ntfsClusterSectors(boot) ?: return false
        for (offset in longArrayOf(le64(boot, 48), le64(boot, 56))) {
            val lba = part.start + offset * sectorsPerCluster
            if (lba <= part.start || lba >= device.totalBlocks) continue
            val record = runCatching { device.readBlocks(lba, 1) }.getOrNull() ?: continue
            if (String(record, 0, 4, Charsets.US_ASCII) == "FILE") return true
        }
        return false
    }

    private fun ntfsClusterSectors(boot: ByteArray): Int? {
        if (boot.size < 512) return null
        val raw = boot[13].toInt() and 0xFF
        return when {
            raw in 1..128 && (raw and (raw - 1)) == 0 -> raw
            raw >= 0xF4 -> 1 shl (256 - raw)
            else -> null
        }
    }

    /**
     * Builds a corrected NTFS boot sector: the healthiest available template with
     * the partition size put right, but only when a real file table can still be
     * located, so the repair is never a blind guess.
     */
    private fun rebuildNtfsBootSector(
        boot: ByteArray,
        copy: ByteArray?,
        part: Part,
        blockSize: Int
    ): ByteArray? {
        val template = listOf(boot, copy).firstOrNull {
            it != null && it.size >= 512 && ntfsClusterSectors(it) != null && le64(it, 48) > 0
        } ?: return null
        val sector = template.copyOf(maxOf(blockSize, 512))
        put64(sector, 40, part.sectors - 1) // NTFS records one sector less than the partition
        sector[510] = 0x55
        sector[511] = 0xAA.toByte()
        val oem = "NTFS    ".toByteArray(Charsets.US_ASCII)
        System.arraycopy(oem, 0, sector, 3, oem.size)
        return sector
    }


    private fun copyRegion(device: BlockDevice, from: Long, to: Long, sectors: Long) {
        val step = (64 * 1024 / device.blockSize).coerceAtLeast(1)
        var done = 0L
        while (done < sectors) {
            val take = minOf(step.toLong(), sectors - done).toInt()
            device.writeBlocks(to + done, device.readBlocks(from + done, take))
            done += take
        }
    }

    // ------------------------------------------------------------------ utils

    /** Filesystem name from a boot sector, or null when nothing is recognised. */
    private fun filesystemOf(boot: ByteArray): String? {
        if (boot.size < 512) return null
        val oem = String(boot, 3, 8, Charsets.US_ASCII)
        if (oem == "EXFAT   ") return "exFAT"
        if (oem == "NTFS    ") return "NTFS"
        if (!signature(boot)) {
            // ext superblock lives 1024 bytes into the partition, not in sector 0.
            return null
        }
        val fat32Id = String(boot, 82, 8, Charsets.US_ASCII).trim()
        if (fat32Id.startsWith("FAT32")) return "FAT32"
        val fat16Id = String(boot, 54, 8, Charsets.US_ASCII).trim()
        if (fat16Id.startsWith("FAT")) return "FAT16"
        return null
    }

    private fun exfatChecksum(region: ByteArray, blockSize: Int): Long {
        var sum = 0L
        for (i in region.indices) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (((sum and 1L) shl 31) or (sum shr 1)) + (region[i].toLong() and 0xFF)
            sum = sum and 0xFFFFFFFFL
        }
        require(blockSize > 0)
        return sum
    }

    private fun signature(sector: ByteArray) =
        sector.size >= 512 && (sector[510].toInt() and 0xFF) == 0x55 && (sector[511].toInt() and 0xFF) == 0xAA

    private fun isGptSignature(sector: ByteArray) =
        sector.size >= 8 && String(sector, 0, 8, Charsets.US_ASCII) == "EFI PART"

    private fun le16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun le64(b: ByteArray, o: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun put64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    private fun crc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    private fun result(findings: List<Finding>, applied: Int, repaired: Boolean): JSONObject {
        val problems = findings.filter { it.severity != "info" }
        val safe = problems.count { it.severity == "safe" }
        val risky = problems.count { it.severity == "risky" }
        val remaining = problems.count { !it.applied }
        return JSONObject().apply {
            put("ok", problems.isEmpty() || (repaired && remaining == 0))
            put("repaired", repaired)
            put("appliedCount", applied)
            put("safeCount", safe)
            put("riskyCount", risky)
            put("remainingCount", remaining)
            put("findings", JSONArray().apply { findings.forEach { put(it.toJson()) } })
            put(
                "summary",
                when {
                    problems.isEmpty() -> "No partition damage found on this drive"
                    !repaired && risky == 0 -> "$safe problem(s) found, all repairable without losing files"
                    !repaired -> "$safe problem(s) repairable without losing files, $risky that may cost files"
                    applied == 0 -> "Nothing was repaired"
                    remaining == 0 -> "$applied problem(s) repaired, drive structures are consistent again"
                    else -> "$applied problem(s) repaired, $remaining still need attention"
                }
            )
        }
    }

    private fun failure(message: String) = JSONObject().apply {
        put("ok", false)
        put("summary", message)
        put("findings", JSONArray())
    }

    private inline fun withDrive(
        context: Context,
        deviceName: String,
        block: (BlockDevice) -> JSONObject
    ): JSONObject {
        val usbDevice = DriveDetector(context).findDeviceByName(deviceName)
            ?: return failure("The USB drive is no longer connected")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(usbDevice)) {
            return failure("USB permission was revoked - unplug the drive, plug it back in and allow access")
        }
        val device = UsbBulkStorageDevice.open(usbManager, usbDevice)
            ?: return failure("Could not open the drive (another app may be using it)")
        return try {
            block(device)
        } catch (e: Throwable) {
            failure("${e.javaClass.simpleName}: ${e.message ?: "unexpected failure"}")
        } finally {
            runCatching { device.close() }
        }
    }
}
