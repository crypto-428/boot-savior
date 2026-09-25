package com.yourapp.USBooter

import android.hardware.usb.UsbDevice
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.yourapp.USBooter.util.BootConfig
import com.yourapp.USBooter.util.BootMode
import com.yourapp.USBooter.util.DriveDetector
import com.yourapp.USBooter.util.Filesystem
import com.yourapp.USBooter.util.FirmwareTarget
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.NtfsCapability
import com.yourapp.USBooter.util.PartitionDefinition
import com.yourapp.USBooter.util.PartitionTableType
import com.yourapp.USBooter.util.UsbDrive
import com.yourapp.USBooter.util.VerificationLevel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class WebAppInterface(private val activity: MainActivity) {

    val detector = DriveDetector(activity.applicationContext)

    @JavascriptInterface
    fun rescanDrives() {
        val devices = detector.listCandidateDrives()
        val jsonArray = JSONArray()
        devices.forEach { device ->
            val obj = JSONObject()
            obj.put("name", device.productName ?: "USB Drive")
            obj.put("deviceName", device.deviceName)
            obj.put("vendorId", device.vendorId)
            obj.put("productId", device.productId)
            jsonArray.put(obj)
        }
        activity.runOnUiThread {
            activity.webView.evaluateJavascript("updateDrives($jsonArray)", null)
        }
    }

    @JavascriptInterface
    fun selectDrive(deviceName: String) {
        val device = detector.findDeviceByName(deviceName)
        if (device == null) {
            activity.showToast("Device not found")
            return
        }

        detector.requestPermission(device) { granted ->
            if (granted) {
                thread {
                    val drive = detector.probeDrive(device)
                    activity.runOnUiThread {
                        if (drive != null) {
                            val obj = JSONObject()
                            obj.put("name", drive.model)
                            obj.put("size", drive.sizeHuman)
                            obj.put("sizeBytes", drive.sizeBytes)
                            obj.put("deviceName", drive.deviceName)
                            activity.webView.evaluateJavascript("onDriveProbed($obj)", null)
                        } else {
                            activity.showToast("Failed to probe drive")
                        }
                    }
                }
            } else {
                activity.showToast("Permission denied")
            }
        }
    }

    @JavascriptInterface
    fun startFormat(configJson: String) {
        try {
            val json = JSONObject(configJson)
            val deviceName = json.getString("deviceName")
            val driveModel = json.getString("driveModel")
            val tableType = if (json.getString("tableType") == "GPT") 
                PartitionTableType.GPT else PartitionTableType.MBR
            
            val partitionsArray = json.getJSONArray("partitions")
            val partitions = mutableListOf<PartitionDefinition>()
            for (i in 0 until partitionsArray.length()) {
                val pJson = partitionsArray.getJSONObject(i)
                val fs = when (pJson.getString("fs").uppercase()) {
                    "FAT32" -> Filesystem.FAT32
                    "EXFAT" -> Filesystem.EXFAT
                    "NTFS" -> Filesystem.NTFS
                    "FAT16" -> Filesystem.FAT16
                    "FAT12" -> Filesystem.FAT12
                    "EXT4" -> Filesystem.EXT4
                    "EXT2" -> Filesystem.EXT2
                    "SWAP", "LINUX_SWAP" -> Filesystem.LINUX_SWAP
                    else -> Filesystem.EXFAT // Fallback
                }
                partitions.add(
                    PartitionDefinition(
                        id = i + 1,
                        label = pJson.optString("label", "DATA"),
                        sizeMB = pJson.optInt("sizeMB", -1),
                        filesystem = fs,
                        isESP = pJson.optBoolean("isESP", false),
                        startLba = if (pJson.has("startLba")) pJson.optLong("startLba") else null,
                        sizeSectors = if (pJson.has("sizeSectors")) pJson.optLong("sizeSectors") else null
                    )
                )
            }

            val config = LayoutConfig(partitions, tableType, json.optBoolean("deepFormat", false))
            val errors = config.validate()
            if (errors.isNotEmpty()) {
                activity.showToast(errors.first())
                return
            }

            // Optional bootable payload: { boot: { uri, name, mode, verify, targetPartitionIndex } }
            val bootJson = json.optJSONObject("boot")
            val bootConfig = if (bootJson != null && bootJson.optString("uri").isNotBlank()) {
                BootConfig(
                    isoUriString = bootJson.getString("uri"),
                    isoDisplayName = bootJson.optString("name", "image.iso"),
                    mode = runCatching { BootMode.valueOf(bootJson.optString("mode", "AUTO")) }
                        .getOrDefault(BootMode.AUTO),
                    firmware = runCatching {
                        FirmwareTarget.valueOf(bootJson.optString("firmware", "BOTH"))
                    }.getOrDefault(FirmwareTarget.BOTH),
                    useRemainingSpace = bootJson.optBoolean("useRemainingSpace", false),
                    dataPartitionFilesystem = when (bootJson.optString("dataFs", "exFAT").uppercase()) {
                        "FAT32" -> Filesystem.FAT32
                        "NTFS" -> Filesystem.NTFS
                        else -> Filesystem.EXFAT
                    },
                    targetPartitionIndex = bootJson.optInt("targetPartitionIndex", 0),
                    verify = bootJson.optBoolean("verify", true),
                    verification = runCatching {
                        VerificationLevel.valueOf(bootJson.optString("verification", "FULL"))
                    }.getOrDefault(VerificationLevel.FULL),
                    persistenceMB = bootJson.optInt("persistenceMB", 0),
                    bootPartitionMB = bootJson.optInt("bootPartitionMB", 0),
                    dataLabel = bootJson.optString("dataLabel", "")
                )
            } else null

            val dryRun = json.optBoolean("dryRun", false)
            activity.startFormatService(deviceName, driveModel, config, bootConfig, dryRun)
        } catch (e: Exception) {
            activity.showToast("Invalid configuration: ${e.message}")
        }
    }

    /** Opens the system file picker so the user can choose an ISO to make the drive bootable. */
    @JavascriptInterface
    fun pickIso() {
        activity.pickIso()
    }

    @JavascriptInterface
    fun safelyRemove(deviceName: String) {
        activity.safelyRemoveDrive(deviceName)
    }

    /**
     * Runs the NTFS self-test and reports whether NTFS formatting is really
     * available, so the UI can disable the option and explain why.
     */
    @JavascriptInterface
    fun ntfsCapability(): String {
        val result = NtfsCapability.probe()
        return JSONObject().apply {
            put("available", result.available)
            put("code", if (result.available) JSONObject.NULL else NtfsCapability.CODE_UNAVAILABLE)
            put("reason", result.reason ?: JSONObject.NULL)
            put("detail", result.detail)
        }.toString()
    }

    /** Stops the flash that is currently running, at the next safe point. */
    @JavascriptInterface
    fun cancelFormat(): Boolean {
        val stopped = com.yourapp.USBooter.service.FormatService.cancelCurrent()
        activity.showToast(if (stopped) "Stopping the flash..." else "Nothing is running")
        return stopped
    }

    /** The plain-text troubleshooting report for the last (or running) flash. */
    @JavascriptInterface
    fun flashReport(): String = com.yourapp.USBooter.util.FlashReport.render()

    /**
     * State of the last interrupted flash, so the UI can offer "resume" (the
     * partitions and filesystems are already valid, only the copy is unfinished)
     * or "roll back" (the table was half written and the drive must be cleared).
     * Returns "null" when there is nothing pending.
     */
    @JavascriptInterface
    fun pendingFlash(): String =
        com.yourapp.USBooter.util.FlashJournal.pending()?.toJson()?.toString() ?: "null"

    /** Forgets the interrupted flash without touching the drive. */
    @JavascriptInterface
    fun discardPendingFlash() {
        com.yourapp.USBooter.util.FlashJournal.clear()
    }

    /**
     * Rollback: clears the partition table and every filesystem signature left
     * by an interrupted flash, so the stick is never left half-partitioned.
     */
    @JavascriptInterface
    fun rollbackDrive(deviceName: String) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - stop it first")
            return
        }
        thread {
            val device = detector.findDeviceByName(deviceName)
            if (device == null) {
                post("onRollbackResult(false, ${quote("The drive is no longer connected")})")
                return@thread
            }
            val usbManager = activity.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val storage = com.yourapp.USBooter.util.UsbBulkStorageDevice.open(usbManager, device)
            if (storage == null) {
                post("onRollbackResult(false, ${quote("Could not open the drive")})")
                return@thread
            }
            val result = runCatching {
                com.yourapp.USBooter.util.FlashJournal.rollback(storage) { pct, detail ->
                    post("onInspectProgress('rollback', $pct, ${quote(detail)})")
                }
            }
            runCatching { storage.close() }
            result.fold(
                onSuccess = { post("onRollbackResult(true, ${quote(it)})") },
                onFailure = { post("onRollbackResult(false, ${quote(it.message ?: "rollback failed")})") }
            )
        }
    }


    /** Saves the report to a file and opens the Android share sheet. */
    @JavascriptInterface
    fun exportReport() {
        activity.exportFlashReport()
    }

    /**
     * Preflight self-test: validates the bundled GRUB/UEFI assets, checks the
     * drive answers reads *and* keeps a written sector, and confirms the payload
     * fits - all before a single byte of the real layout is written.
     */
    @JavascriptInterface
    fun runPreflight(configJson: String) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        thread {
            val json = runCatching {
                val cfg = JSONObject(configJson)
                com.yourapp.USBooter.util.DriveInspector.preflight(
                    activity.applicationContext,
                    cfg.getString("deviceName"),
                    cfg.optString("uri").ifBlank { null },
                    runCatching { BootMode.valueOf(cfg.optString("mode", "AUTO")) }.getOrNull(),
                    cfg.optInt("persistenceMB", 0)
                ) { pct, detail -> post("onInspectProgress('preflight', $pct, ${quote(detail)})") }
            }.getOrElse {
                JSONObject().put("ok", false).put("summary", "Preflight failed: ${it.message}")
            }
            post("onPreflightResult($json)")
        }
    }

    /**
     * Re-reads and re-hashes the boot and UEFI loader files that are already on
     * the drive, then appends the outcome to the exportable report. Nothing is
     * rewritten.
     */
    @JavascriptInterface
    fun rerunVerification(configJson: String) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        thread {
            val json = runCatching {
                val cfg = JSONObject(configJson)
                com.yourapp.USBooter.util.DriveInspector.reverify(
                    activity.applicationContext,
                    cfg.getString("deviceName"),
                    cfg.getString("uri"),
                    cfg.optBoolean("deep", true),
                    { false }
                ) { pct, detail -> post("onInspectProgress('verify', $pct, ${quote(detail)})") }
            }.getOrElse {
                JSONObject().put("ok", false).put("summary", "Verification failed: ${it.message}")
            }
            post("onReverifyResult($json)")
        }
    }

    /** Set by the UI to stop a running full-drive scan. */
    @Volatile
    private var repairCancelled = false

    /** Asks a running scan or repair to stop as soon as the current window is done. */
    @JavascriptInterface
    fun cancelRepairScan() {
        repairCancelled = true
    }

    /**
     * Looks for partition-table and filesystem damage without writing a single
     * byte. Every finding says whether it can be repaired from a spare copy the
     * drive already carries (safe) or only by guessing (risky). When [deep] is
     * true every sector of the drive is read, which is slow but complete.
     */
    @JavascriptInterface
    fun scanPartitions(deviceName: String, deep: Boolean) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        repairCancelled = false
        thread {
            val json = runCatching {
                com.yourapp.USBooter.util.PartitionRepair.scan(
                    activity.applicationContext,
                    deviceName,
                    deep,
                    { repairCancelled }
                ) { pct, detail -> post("onRepairProgress($pct, ${quote(detail)})") }
            }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("summary", "Partition scan failed: ${it.message ?: "unknown error"}")
                    .put("findings", JSONArray())
            }
            post("onRepairScan($json)")
        }
    }

    /**
     * Applies the repairs. Safe repairs rebuild a structure from a copy already
     * on the drive and cannot lose files; risky ones are applied only when the
     * user has explicitly accepted the possible loss ([allowRisky]). When
     * [allowDataLoss] is true, a partition whose filesystem cannot be recovered is
     * rebuilt in place as an empty NTFS volume of the same size - the files in that
     * partition are erased, the rest of the drive is untouched.
     */
    @JavascriptInterface
    fun repairPartitions(
        deviceName: String,
        allowRisky: Boolean,
        deep: Boolean,
        allowDataLoss: Boolean,
        targetFindingId: String
    ) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        repairCancelled = false
        thread {
            val json = runCatching {
                com.yourapp.USBooter.util.PartitionRepair.repair(
                    activity.applicationContext,
                    deviceName,
                    allowRisky,
                    deep,
                    allowDataLoss,
                    targetFindingId,
                    { repairCancelled }
                ) { pct, detail -> post("onRepairProgress($pct, ${quote(detail)})") }
            }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("repaired", true)
                    .put("summary", "Partition repair failed: ${it.message ?: "unknown error"}")
                    .put("findings", JSONArray())
            }
            post("onRepairResult($json)")
        }
    }

    /**
     * Last resort. Wipes the drive, writes a fresh partition table and filesystem
     * and loses every file. The UI must confirm this twice before calling it.
     */
    @JavascriptInterface
    fun rebuildDriveDestructively(deviceName: String, filesystem: String, label: String) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        thread {
            val json = runCatching {
                com.yourapp.USBooter.util.PartitionRepair.destructiveRebuild(
                    activity.applicationContext,
                    deviceName,
                    filesystem,
                    label
                ) { pct, detail -> post("onRepairProgress($pct, ${quote(detail)})") }
            }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("destructive", true)
                    .put("summary", "Drive rebuild failed: ${it.message ?: "unknown error"}")
                    .put("findings", JSONArray())
            }
            post("onRepairResult($json)")
        }
    }

    // ------------------------------------------------- partition editor bridge

    /** Current partitions with the size limits the editor may offer. */
    @JavascriptInterface
    fun listPartitions(deviceName: String) = editorTask {
        com.yourapp.USBooter.util.PartitionManager.list(activity.applicationContext, deviceName)
    }

    /** Removes a table entry; the sectors themselves are left untouched. */
    @JavascriptInterface
    fun deletePartition(deviceName: String, index: Int) = editorTask {
        com.yourapp.USBooter.util.PartitionManager.delete(activity.applicationContext, deviceName, index)
    }

    /** Shrinks or extends a partition (and NTFS inside it) in sectors. */
    @JavascriptInterface
    fun resizePartition(deviceName: String, index: Int, newSectors: String, allowDataLoss: Boolean) =
        editorTask {
            com.yourapp.USBooter.util.PartitionManager.resize(
                activity.applicationContext,
                deviceName,
                index,
                newSectors.toLongOrNull() ?: -1L,
                allowDataLoss
            )
        }

    /** Creates and formats a partition in free space. */
    @JavascriptInterface
    fun createPartition(
        deviceName: String,
        startLba: String,
        sectors: String,
        filesystem: String,
        label: String
    ) = editorTask {
        com.yourapp.USBooter.util.PartitionManager.create(
            activity.applicationContext,
            deviceName,
            startLba.toLongOrNull() ?: -1L,
            sectors.toLongOrNull() ?: -1L,
            filesystem,
            label
        )
    }

    /** Moving a partition is refused on purpose; the reason is shown to the user. */
    @JavascriptInterface
    fun movePartition() = editorTask { com.yourapp.USBooter.util.PartitionManager.move() }

    private fun editorTask(work: () -> JSONObject) {
        if (com.yourapp.USBooter.service.FormatService.isRunning) {
            showToast("A flash is running - wait for it to finish")
            return
        }
        thread {
            val json = runCatching { work() }.getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("summary", "Partition editing failed: ${it.message ?: "unknown error"}")
                    .put("partitions", JSONArray())
            }
            post("onPartitionEditor($json)")
        }
    }


    private fun post(js: String) = activity.runOnUiThread {
        activity.webView.evaluateJavascript(js, null)
    }


    private fun quote(value: String): String = JSONObject.quote(value)


    /** Saves the detailed log through the system file picker (user picks the location). */
    @JavascriptInterface
    fun saveReportToFile() {
        activity.saveFlashLogToFile()
    }


    @JavascriptInterface
    fun showToast(message: String) {
        activity.showToast(message)
    }
}
