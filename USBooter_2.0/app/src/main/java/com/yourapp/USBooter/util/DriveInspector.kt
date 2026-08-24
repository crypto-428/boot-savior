package com.yourapp.USBooter.util

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-mostly operations the user can run outside a flash: the preflight
 * self-test (before writing) and "re-run verification" (after writing).
 *
 * Both open the drive themselves, do their work on the calling thread and close
 * everything again, so they can never collide with a running flash - the caller
 * refuses to start when [com.yourapp.USBooter.service.FormatService.isRunning].
 */
object DriveInspector {

    /** Validates assets, drive access and payload fit. Nothing is left changed on the drive. */
    fun preflight(
        context: Context,
        deviceName: String,
        isoUriString: String?,
        mode: BootMode?,
        persistenceMB: Int,
        progress: (Int, String) -> Unit
    ): JSONObject = withDrive(context, deviceName) { device ->
        var isoInfo: IsoInfo? = null
        var source: IsoSource? = null
        try {
            if (!isoUriString.isNullOrBlank()) {
                progress(5, "Re-reading the selected image")
                source = runCatching { IsoSource.open(context, Uri.parse(isoUriString)) }.getOrNull()
                isoInfo = source?.let { runCatching { IsoAnalyzer.analyze(it) }.getOrNull() }
            }
            val result = Preflight.run(
                device, context.assets, isoInfo,
                isoInfo?.resolveMode(mode ?: BootMode.AUTO) ?: mode,
                persistenceMB, progress
            )
            result.toJson().apply { put("summary", result.summary()) }
        } finally {
            source?.close()
        }
    }

    /**
     * Re-reads and re-hashes the boot/loader files that are already on the drive
     * and appends the outcome to the exported report. Nothing is written, so it
     * is safe to run as often as the user likes.
     */
    fun reverify(
        context: Context,
        deviceName: String,
        isoUriString: String,
        deep: Boolean,
        isCancelled: () -> Boolean,
        progress: (Int, String) -> Unit
    ): JSONObject = withDrive(context, deviceName) { device ->
        val source = runCatching { IsoSource.open(context, Uri.parse(isoUriString)) }.getOrNull()
            ?: return@withDrive failure("The image could not be opened again - pick the ISO once more")
        try {
            progress(5, "Reading the image directory")
            val directory = runCatching { ImageDirectory.read(source) }.getOrNull()
            val entries = directory?.entries.orEmpty()

            device.synchronizeCache()
            progress(10, "Reading the partition table from the drive")
            val partitionStart = firstFat32PartitionStart(device)

            val problems = mutableListOf<String>()
            val notes = mutableListOf<String>()
            val efiResults = JSONArray()
            var checkedFiles = 0

            if (partitionStart == null) {
                // No FAT32 partition: the drive holds a raw clone of the image.
                notes += "No FAT32 partition found: verifying the drive as a raw image clone"
                val report = BootFileVerifier.verifyRawClone(device, source, deep, isCancelled) { pct, detail ->
                    progress(10 + (pct * 80) / 100, detail)
                }
                checkedFiles = report.checkedFiles
                problems += report.problems
                notes += report.notes
            } else {
                notes += "Verifying the FAT32 boot partition at LBA $partitionStart"
                IoMonitor.verified(partitionStart, partitionStart, "boot partition start")
                val report = BootFileVerifier.verifyFileCopy(
                    device, partitionStart, source, entries, deep, isCancelled
                ) { pct, detail -> progress(10 + (pct * 70) / 100, detail) }
                checkedFiles = report.checkedFiles
                problems += report.problems
                notes += report.notes

                progress(85, "Re-hashing the UEFI loader")
                val fat = runCatching { Fat32Reader(device, partitionStart) }.getOrNull()
                if (fat != null) {
                    val (efiReport, results) = BootFileVerifier.verifyEfiLoaders(
                        source, entries,
                        { path ->
                            val found = runCatching { fat.find(path) }.getOrNull()
                            if (found == null) null
                            else found.size to { off: Long, len: Int -> fat.read(found, off, len) }
                        }
                    ) { pct, detail -> progress(85 + (pct * 10) / 100, detail) }
                    problems += efiReport.problems
                    notes += efiReport.notes
                    results.forEach {
                        efiResults.put(JSONObject().apply {
                            put("path", it.path)
                            put("present", it.present)
                            put("sizeOk", it.sizeOk)
                            put("sha256", it.sha256)
                        })
                        FlashReport.step(
                            "Re-run · UEFI loader /${it.path}",
                            if (it.present && it.sizeOk) "verified, SHA-256 ${it.sha256}" else "MISSING or wrong size"
                        )
                    }
                }
            }

            progress(100, "Verification finished")
            val ok = problems.isEmpty()
            FlashReport.step(
                "Re-run verification",
                (if (ok) "passed" else "FAILED — " + problems.joinToString(" · ")) +
                    " ($checkedFiles files, ${if (deep) "full hash" else "sampled hash"})"
            )
            notes.forEach { FlashReport.step("Re-run · note", it) }
            JSONObject().apply {
                put("ok", ok)
                put("checkedFiles", checkedFiles)
                put("deep", deep)
                put("problems", JSONArray(problems))
                put("notes", JSONArray(notes))
                put("efi", efiResults)
                put("io", IoMonitor.snapshot())
                put(
                    "summary",
                    if (ok) "$checkedFiles boot files re-read and re-hashed successfully"
                    else problems.first()
                )
            }
        } finally {
            source.close()
        }
    }

    /** LBA of the first partition that carries a FAT32 boot sector, or null. */
    private fun firstFat32PartitionStart(device: UsbBulkStorageDevice): Long? {
        val mbr = runCatching { device.readBlocks(0, 1) }.getOrNull() ?: return null
        if ((mbr[510].toInt() and 0xFF) != 0x55 || (mbr[511].toInt() and 0xFF) != 0xAA) return null
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val start = (0 until 4).fold(0L) { acc, b ->
                acc or ((mbr[base + 8 + b].toLong() and 0xFF) shl (8 * b))
            }
            if (start <= 0 || start >= device.totalBlocks) continue
            val boot = runCatching { device.readBlocks(start, 1) }.getOrNull() ?: continue
            val fsType = String(boot, 82, 8, Charsets.US_ASCII).trim()
            val signature = (boot[510].toInt() and 0xFF) == 0x55 && (boot[511].toInt() and 0xFF) == 0xAA
            if (signature && fsType.startsWith("FAT32")) return start
        }
        return null
    }

    private fun failure(message: String) = JSONObject().apply {
        put("ok", false)
        put("summary", message)
        put("problems", JSONArray().put(message))
    }

    private inline fun withDrive(
        context: Context,
        deviceName: String,
        block: (UsbBulkStorageDevice) -> JSONObject
    ): JSONObject {
        val detector = DriveDetector(context)
        val usbDevice = detector.findDeviceByName(deviceName)
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
