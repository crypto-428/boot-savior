package com.yourapp.USBooter.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Serializable

/**
 * A USB mass-storage device found via the standard Android USB Host API.
 * [deviceName] is UsbDevice.deviceName (e.g. "/dev/bus/usb/001/002") - a stable
 * key used to re-look-up the live UsbDevice later, since UsbDevice itself isn't
 * Serializable and can't be passed through Fragment arguments.
 */
data class UsbDrive(
    val deviceName: String,
    val model: String,
    val sizeBytes: Long,
    val sizeHuman: String,
    val blockSize: Int,
    val totalBlocks: Long
) : Serializable

class DriveDetector(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /**
     * Lists attached USB mass-storage devices. This does NOT require permission -
     * enumerating what's plugged in is always allowed; only opening a connection
     * to read/write it needs the user's OK.
     */
    fun listCandidateDrives(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { UsbBulkStorageDevice.isMassStorageDevice(it) }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Requests USB permission for [device], showing the system's "Allow app to
     * access device?" dialog if not already granted. [onResult] is called with
     * true/false once the user responds (or immediately if already granted).
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        val action = "${context.packageName}.USB_PERMISSION"
        // Android 14 rejects mutable *implicit* PendingIntents outright, which is
        // why USB permission silently failed on newer phones. Explicitly scoping
        // the intent to our own package keeps it legal on every release, and
        // FLAG_MUTABLE only exists from API 31 (it is the default before that).
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != action) return
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Opens [device] (permission must already be granted) and reads its real
     * capacity/block size over raw USB, returning a fully-populated UsbDrive.
     */
    fun probeDrive(device: UsbDevice): UsbDrive? {
        val storage = UsbBulkStorageDevice.open(usbManager, device) ?: return null
        try {
            val sizeBytes = storage.totalBlocks * storage.blockSize
            return UsbDrive(
                deviceName = device.deviceName,
                model = device.productName ?: "USB Drive",
                sizeBytes = sizeBytes,
                sizeHuman = humanReadableSize(sizeBytes),
                blockSize = storage.blockSize,
                totalBlocks = storage.totalBlocks
            )
        } finally {
            storage.close()
        }
    }

    fun findDeviceByName(deviceName: String): UsbDevice? {
        return usbManager.deviceList.values.find { it.deviceName == deviceName }
    }

    private fun humanReadableSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return String.format("%.1f GB", gb)
        val mb = bytes / 1_000_000.0
        return String.format("%.0f MB", mb)
    }
}
