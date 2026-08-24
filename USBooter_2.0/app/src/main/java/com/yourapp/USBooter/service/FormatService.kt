package com.yourapp.USBooter.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yourapp.USBooter.MainActivity
import com.yourapp.USBooter.R
import android.net.Uri
import com.yourapp.USBooter.util.BootConfig
import com.yourapp.USBooter.util.CopyDiagnostics
import com.yourapp.USBooter.util.IsoSource
import com.yourapp.USBooter.util.DriveDetector
import com.yourapp.USBooter.util.FlashReport
import com.yourapp.USBooter.util.FormatEngine
import com.yourapp.USBooter.util.FormatError
import com.yourapp.USBooter.util.FormatErrors
import com.yourapp.USBooter.util.FormatStage
import com.yourapp.USBooter.util.IoMonitor
import com.yourapp.USBooter.util.Filesystem
import com.yourapp.USBooter.util.NtfsCapability
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.UsbBulkStorageDevice
import kotlin.concurrent.thread

class FormatService : Service() {

    companion object {
        const val CHANNEL_ID = "format_progress"
        const val NOTIFICATION_ID = 1
        const val FORMAT_PROGRESS_ACTION = "com.yourapp.USBooter.FORMAT_PROGRESS"
        private const val TAG = "FormatService"

        /** The engine of the flash that is running right now, so the UI can stop it. */
        @Volatile
        private var activeEngine: FormatEngine? = null

        /** Asks the running flash to stop at the next safe point. Returns false if nothing runs. */
        fun cancelCurrent(): Boolean {
            val engine = activeEngine ?: return false
            engine.cancel()
            return true
        }

        val isRunning: Boolean get() = activeEngine != null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ kills the process if a service started with
        // startForegroundService() does not call startForeground() within a few
        // seconds. Every early "return" below used to skip it, which is why the
        // Wipe button looked dead on newer phones: the service was killed before
        // it could report anything. So we go foreground first, then validate.
        goForeground("Preparing...", 0, "")

        val deviceName = intent?.getStringExtra("drive_device_name")
            ?: return abort("No drive was passed to the format service", "E-CFG-01", FormatStage.PREPARE)
        val driveModel = intent.getStringExtra("drive_model") ?: "USB Drive"
        val config = intent.getSerializableExtra("config") as? LayoutConfig
            ?: return abort("The partition layout could not be read", "E-CFG-01", FormatStage.PREPARE)

        val detector = DriveDetector(applicationContext)
        val usbDevice = detector.findDeviceByName(deviceName)
            ?: return abort("The USB drive is no longer connected", "E-USB-02", FormatStage.PREPARE)

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(usbDevice)) {
            return abort(
                "USB permission was revoked - unplug the drive, plug it back in and allow access",
                "E-USB-03", FormatStage.PERMISSION
            )
        }

        val bootConfig = intent.getSerializableExtra("boot_config") as? BootConfig
        val dryRun = intent.getBooleanExtra("dry_run", false)

        CopyDiagnostics.reset()
        IoMonitor.reset()
        FlashReport.start("USBooter flash report - $driveModel", dryRun)
        FlashReport.fact("Drive", "$driveModel ($deviceName)")
        FlashReport.fact("Partition table", config.tableType.displayName)
        FlashReport.fact("Partitions", config.partitions.joinToString { "${it.label} ${it.filesystem.displayName}" })
        bootConfig?.let {
            FlashReport.fact("Image", it.isoDisplayName)
            FlashReport.fact("Write mode", it.mode.name)
            FlashReport.fact("Firmware target", it.firmware.name)
            FlashReport.fact("Verification", it.verification.name)
        }

        val storage = UsbBulkStorageDevice.open(usbManager, usbDevice)
            ?: return abort("Could not open the drive for writing (another app may be using it)", "E-USB-04", FormatStage.PREPARE)

        // NTFS is written by our own formatter, so verify it can really produce a
        // mountable volume on this drive's sector size before touching the disk.
        if (config.partitions.any { it.filesystem == Filesystem.NTFS }) {
            val ntfs = NtfsCapability.probe(storage.blockSize)
            if (!ntfs.available) {
                storage.close()
                return abort(
                    "NTFS formatting is not available in this build",
                    NtfsCapability.CODE_UNAVAILABLE, FormatStage.FILESYSTEM,
                    technical = "NTFS self-test failed (${ntfs.reason}): ${ntfs.detail}"
                )
            }
        }

        thread {
            val isoSource = bootConfig?.let {
                runCatching { IsoSource.open(applicationContext, Uri.parse(it.isoUriString)) }.getOrNull()
            }
            try {
                if (bootConfig != null && isoSource == null) {
                    publish(
                        "Error: the selected ISO can no longer be read", -1,
                        "Pick the image again - Android dropped the permission for that file",
                        FormatErrors.of(
                            "E-ISO-01", FormatStage.ISO,
                            "IsoSource.open returned null for ${bootConfig.isoUriString}",
                            "The selected ISO can no longer be read"
                        )
                    )
                } else {
                    val engine = FormatEngine(
                        storage, driveModel, config, bootConfig, isoSource,
                        applicationContext.assets, dryRun
                    )
                    activeEngine = engine
                    engine.format { status, progress, details -> publish(status, progress, details) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Format crashed", e)
                publish(
                    "Error: ${e.message ?: e.javaClass.simpleName}", -1,
                    "The drive was not fully written",
                    FormatErrors.classify(e)
                )
            } finally {
                activeEngine = null
                isoSource?.close()
                storage.close()
                // The progress notification is ongoing while the flash runs, so it
                // has to be explicitly removed here. Detaching it (the old
                // behaviour) left "Formatting USB Drive" stuck in the shade for
                // ever after a success, a failure or a cancel.
                clearNotification()
                stopSelf()
            }

        }

        return START_NOT_STICKY
    }

    /** Sends progress to the WebView and mirrors it in the notification. */
    private fun publish(
        status: String,
        progress: Int,
        details: String,
        error: FormatError? = null
    ) {
        // Terminal states (failure = negative, success = 100) must not leave an
        // ongoing progress notification behind: drop it instead of updating it.
        if (progress < 0 || progress >= 100) clearNotification() else notify(status, progress, details)
        FlashReport.step(status, details)

        if (progress < 0) FlashReport.finish("failed: $status")
        else if (progress >= 100) FlashReport.finish("success")
        // Any negative progress is a failure: always ship a structured error so the
        // UI can name the failing step, the code and the suggested next actions.
        val report = error ?: if (progress < 0) FormatErrors.classifyMessage(status, details) else null
        val broadcastIntent = Intent(FORMAT_PROGRESS_ACTION).apply {
            putExtra("status", status)
            putExtra("progress", progress)
            putExtra("details", details)
            // Live read/write position so the UI can show the exact LBA range
            // being written, verified, or the one that just failed.
            putExtra("io", IoMonitor.snapshot().toString())
            report?.let { putExtra("error", it.toJson().toString()) }
            // Always ship the copy diagnostics with a failure: they explain why
            // no files ended up on the drive (empty UDF tree, ISO fallback,
            // parsed file count, per-partition totals).
            if (progress < 0) {
                val diagnostics = CopyDiagnostics.toJson()
                if (diagnostics.length() > 0) putExtra("diagnostics", diagnostics.toString())
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
    }

    private fun abort(
        reason: String,
        code: String = "E-UNK-01",
        stage: FormatStage = FormatStage.PREPARE,
        technical: String = reason
    ): Int {
        publish(
            "Error: $reason", -1, "Nothing was written to the drive",
            FormatErrors.of(code, stage, technical, reason)
        )
        clearNotification()
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Takes the service out of the foreground *and* cancels the progress
     * notification. Both are needed: stopForeground alone can leave the last
     * posted notification visible, which is how "Formatting USB Drive" used to
     * stay in the shade after the flash had finished or been aborted.
     */
    private fun clearNotification() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "stopForeground failed", e)
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel notification failed", e)
        }
    }

    override fun onDestroy() {
        clearNotification()
        super.onDestroy()
    }


    private fun goForeground(status: String, progress: Int, details: String) {
        val notification = buildNotification(status, progress, details)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            // Never let a notification problem stop a flash that is already going.
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun notify(status: String, progress: Int, details: String) {
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(status, progress, details))
        } catch (e: Throwable) {
            Log.w(TAG, "notify failed", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Format Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, progress: Int, details: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Formatting USB Drive")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$details"))
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setOngoing(progress in 0..99)
            .setSmallIcon(R.drawable.ic_usb)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
            .build()
}
