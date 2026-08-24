package com.yourapp.USBooter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import com.yourapp.USBooter.R
import com.yourapp.USBooter.util.DriveDetector
import com.yourapp.USBooter.util.SafetyValidator
import com.yourapp.USBooter.util.UsbDrive
import kotlin.concurrent.thread

class DriveSelectionFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var refreshButton: MaterialButton
    private lateinit var noRootWarning: MaterialCardView
    private lateinit var emptyState: MaterialTextView
    private lateinit var detector: DriveDetector

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_drive_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        detector = DriveDetector(requireContext().applicationContext)
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = "Select Drive"

        recyclerView = view.findViewById(R.id.drive_list)
        refreshButton = view.findViewById(R.id.refresh_button)
        noRootWarning = view.findViewById(R.id.no_root_warning)
        emptyState = view.findViewById(R.id.empty_state)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        requestNotificationPermissionIfNeeded()

        val usbHostSupported = requireContext().packageManager
            .hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        if (!usbHostSupported) {
            noRootWarning.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            refreshButton.visibility = View.GONE
        } else {
            refreshButton.setOnClickListener { refreshDrives() }
            refreshDrives()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    requireActivity(),
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun refreshDrives() {
        val candidates = detector.listCandidateDrives()
        if (candidates.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }
        emptyState.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        recyclerView.adapter = UsbDeviceAdapter(candidates) { device ->
            onDriveSelected(device)
        }
    }

    private fun onDriveSelected(device: UsbDevice) {
        detector.requestPermission(device) { granted ->
            if (!isAdded) return@requestPermission
            if (!granted) {
                Toast.makeText(
                    requireContext(),
                    "USB permission denied - can't access this drive",
                    Toast.LENGTH_LONG
                ).show()
                return@requestPermission
            }

            // Opening the device and reading its capacity talks to the drive over
            // USB and can block briefly - keep it off the main thread.
            thread {
                val drive = detector.probeDrive(device)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (drive == null) {
                        Toast.makeText(
                            requireContext(),
                            "Couldn't read this drive - unplug and try again",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    validateAndProceed(drive)
                }
            }
        }
    }

    private fun validateAndProceed(drive: UsbDrive) {
        val validation = SafetyValidator().validateDriveSelection(drive)
        if (!validation.isSafe) {
            val failedChecks = validation.checks.filter { !it.passed }
            val message = "Safety checks failed:\n\n" +
                failedChecks.joinToString("\n") { "• ${it.description}" }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            return
        }

        val fragment = PartitionConfigFragment().apply {
            arguments = Bundle().apply {
                putSerializable("drive", drive)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}

// Simple adapter for the candidate USB device list (before permission/probing)
class UsbDeviceAdapter(
    private val devices: List<UsbDevice>,
    private val onDeviceClick: (UsbDevice) -> Unit
) : RecyclerView.Adapter<UsbDeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val modelText: MaterialTextView = view.findViewById(R.id.drive_model)
        val sizeText: MaterialTextView = view.findViewById(R.id.drive_size)
        val deviceText: MaterialTextView = view.findViewById(R.id.drive_device)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drive, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.modelText.text = device.productName ?: "USB Drive"
        holder.sizeText.text = "Tap to connect"
        holder.deviceText.text = device.deviceName
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount() = devices.size
}
