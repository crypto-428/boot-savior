package com.yourapp.USBooter.util

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything that can go wrong *before* a single byte is written, checked in a
 * few seconds and reported as a list of named results:
 *
 *  1. the bundled GRUB 2 BIOS images are present and match their SHA-256,
 *  2. the image really carries an EFI loader for the UEFI half of the plan,
 *  3. the drive answers reads across its whole capacity,
 *  4. the drive accepts a write and gives the same bytes back (tested on a
 *     scratch sector whose original content is restored immediately),
 *  5. the payload, plus FAT overhead and any persistence volume, fits.
 *
 * Nothing here modifies the layout of the drive; check 4 writes one sector and
 * puts the original bytes back, which is the only way to catch the USB bridges
 * that silently swallow writes.
 */
object Preflight {

    data class Check(val name: String, val ok: Boolean, val detail: String, val fatal: Boolean = true)

    data class Result(val checks: List<Check>) {
        val ok: Boolean get() = checks.none { !it.ok && it.fatal }
        val warnings: Int get() = checks.count { !it.ok && !it.fatal }

        fun toJson(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("warnings", warnings)
            put("checks", JSONArray().apply {
                checks.forEach {
                    put(JSONObject().apply {
                        put("name", it.name)
                        put("ok", it.ok)
                        put("detail", it.detail)
                        put("fatal", it.fatal)
                    })
                }
            })
        }

        fun summary(): String =
            if (ok && warnings == 0) "All ${checks.size} preflight checks passed"
            else if (ok) "Passed with $warnings warning(s)"
            else "Failed: " + checks.first { !it.ok && it.fatal }.name
    }

    fun run(
        device: UsbBulkStorageDevice,
        assets: AssetManager?,
        isoInfo: IsoInfo?,
        mode: BootMode?,
        persistenceMB: Int,
        progress: (Int, String) -> Unit = { _, _ -> }
    ): Result {
        val checks = mutableListOf<Check>()
        val blockSize = device.blockSize
        val totalBytes = device.totalBlocks * blockSize

        checks += Check(
            "Drive geometry",
            device.totalBlocks > 0 && blockSize in 512..8192,
            "${device.totalBlocks} sectors of $blockSize B (${totalBytes / 1_000_000} MB)"
        )

        // 1. Bundled bootloader assets
        progress(10, "Hashing the bundled GRUB 2 images")
        val needsGrub = mode == null || mode == BootMode.UNIVERSAL || mode == BootMode.WINDOWS || mode == BootMode.AUTO
        if (assets == null) {
            checks += Check("Bundled GRUB assets", false, "The app assets are not available", fatal = needsGrub)
        } else {
            val summary = runCatching { GrubBiosInstaller.validateAssets(assets) }
            checks += Check(
                "Bundled GRUB assets",
                summary.isSuccess,
                summary.getOrElse { it.message ?: "unknown asset problem" },
                fatal = needsGrub
            )
            val gap = runCatching { GrubBiosInstaller.requiredGapSectors(assets, blockSize) }.getOrNull()
            if (gap != null) {
                checks += Check(
                    "MBR embedding gap",
                    gap < 2048,
                    "core.img needs $gap sectors; the layout reserves 2048 before the first partition",
                    fatal = needsGrub
                )
            }
        }

        // 2. UEFI loader inside the image
        progress(25, "Looking for the UEFI loader in the image")
        if (isoInfo != null) {
            checks += Check(
                "UEFI loader in the image",
                isoInfo.hasEfiBootFile || isoInfo.hasEfiBoot,
                if (isoInfo.hasEfiBootFile) "EFI/BOOT/BOOT*.EFI found in the file tree"
                else if (isoInfo.hasEfiBoot) "El Torito advertises an EFI entry, but no EFI/BOOT file was parsed"
                else "No EFI loader: this drive can only boot on legacy BIOS",
                fatal = false
            )
            checks += Check(
                "Legacy BIOS boot path",
                isoInfo.canBiosBootUniversal || isoInfo.isHybrid,
                if (isoInfo.canBiosBootUniversal) "GRUB can chainload what this image ships"
                else "Nothing GRUB can start on a legacy BIOS was found",
                fatal = false
            )
            checks += Check(
                "Image directory readable",
                isoInfo.fileCount > 0,
                "${isoInfo.fileCount} files parsed from the ${isoInfo.directoryFormat} tree",
                fatal = mode != BootMode.RAW_CLONE
            )
        }

        // 3. Read access across the medium
        progress(45, "Reading sectors across the drive")
        val probes = listOf(0L, device.totalBlocks / 4, device.totalBlocks / 2, device.totalBlocks - 8)
            .filter { it in 0 until device.totalBlocks }
            .distinct()
        val readFailure = probes.firstNotNullOfOrNull { lba ->
            val attempt = runCatching { device.readBlocks(lba, 1) }
            if (attempt.isFailure) "LBA $lba: ${attempt.exceptionOrNull()?.message}" else null
        }
        checks += Check(
            "Read access",
            readFailure == null,
            readFailure ?: "Sectors ${probes.joinToString(", ")} all answered"
        )

        // 4. Write access, on a scratch sector that is restored right after
        progress(65, "Testing write and read-back on a scratch sector")
        checks += writeReadBackCheck(device)

        // 5. Payload fit
        progress(85, "Checking the payload fits")
        if (isoInfo != null) {
            val persistenceBytes = persistenceMB.coerceAtLeast(0).toLong() * 1_000_000
            val needed = when (mode) {
                BootMode.RAW_CLONE -> isoInfo.sizeBytes
                else -> (isoInfo.sizeBytes * 115) / 100 + 256L * 1024 * 1024 + persistenceBytes
            }
            checks += Check(
                "Payload fit",
                needed <= totalBytes,
                "${needed / 1_000_000} MB needed of ${totalBytes / 1_000_000} MB available" +
                    (if (persistenceMB > 0) " (includes ${persistenceMB} MB persistence)" else "")
            )
            if (persistenceMB > 0) {
                checks += Check(
                    "Persistence compatible",
                    isoInfo.supportsPersistence,
                    if (isoInfo.supportsPersistence)
                        "${isoInfo.persistenceFamily} initrd will pick up the '${isoInfo.persistenceLabel}' volume"
                    else "This image has no casper/live initrd - the persistence partition would be ignored",
                    fatal = false
                )
            }
            if (isoInfo.largestFileBytes >= IsoInfo.FAT32_FILE_LIMIT) {
                checks += Check(
                    "FAT32 4 GB limit",
                    isoInfo.needsSplitLayout,
                    "Largest file is ${isoInfo.largestFileBytes / (1024 * 1024)} MB" +
                        (if (isoInfo.needsSplitLayout) "; it will be split into install.swm parts or moved to exFAT"
                        else "; it cannot be placed on FAT32"),
                    fatal = !isoInfo.needsSplitLayout
                )
            }
        }

        progress(100, "Preflight finished")
        val result = Result(checks)
        FlashReport.step("Preflight self-test", result.summary())
        checks.forEach { FlashReport.step("Preflight · ${it.name}", (if (it.ok) "ok — " else "FAILED — ") + it.detail) }
        return result
    }

    /**
     * Writes a known pattern to a sector the layout never uses (the very last
     * one), reads it back and then restores the original content. A bridge that
     * caches or drops writes fails here in a second instead of after a 20-minute
     * flash.
     */
    private fun writeReadBackCheck(device: UsbBulkStorageDevice): Check {
        val lba = device.totalBlocks - 1
        if (lba < 0) return Check("Write access", false, "The drive reported no capacity")
        val original = runCatching { device.readBlocks(lba, 1) }.getOrElse {
            return Check("Write access", false, "Could not read the scratch sector at LBA $lba: ${it.message}")
        }
        val pattern = ByteArray(device.blockSize) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        return try {
            device.writeBlocks(lba, pattern)
            device.synchronizeCache()
            val back = device.readBlocks(lba, 1)
            val matched = back.size >= pattern.size && (pattern.indices.all { back[it] == pattern[it] })
            if (matched) IoMonitor.verified(lba, lba, "preflight scratch sector")
            else IoMonitor.failed(lba, lba, "preflight scratch sector did not read back")
            Check(
                "Write access",
                matched,
                if (matched) "LBA $lba accepted a write and read the same bytes back"
                else "LBA $lba did not return what was written - this USB bridge drops or caches writes"
            )
        } catch (e: Exception) {
            Check("Write access", false, "LBA $lba refused the write: ${e.message}")
        } finally {
            runCatching {
                device.writeBlocks(lba, original)
                device.synchronizeCache()
            }
        }
    }
}
