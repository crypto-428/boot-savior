package com.yourapp.USBooter.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.yourapp.USBooter.R
import com.yourapp.USBooter.service.FormatService
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.UsbDrive

class FormatProgressFragment : Fragment() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var cancelButton: MaterialButton
    private lateinit var drive: UsbDrive
    private lateinit var config: LayoutConfig

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra("status") ?: return
            val progress = intent.getIntExtra("progress", 0)
            val details = intent.getStringExtra("details") ?: ""

            statusText.text = status
            detailText.text = details

            if (progress == -1) {
                progressBar.isIndeterminate = false
                cancelButton.text = "Close"
                cancelButton.setOnClickListener { parentFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE) }
            } else {
                progressBar.isIndeterminate = false
                progressBar.progress = progress
                if (progress == 100) {
                    cancelButton.text = "Done"
                    cancelButton.setOnClickListener { parentFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drive = arguments?.getSerializable("drive") as UsbDrive
        config = arguments?.getSerializable("config") as LayoutConfig
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_format_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        progressBar = view.findViewById(R.id.progress_bar)
        statusText = view.findViewById(R.id.status_text)
        detailText = view.findViewById(R.id.detail_text)
        cancelButton = view.findViewById(R.id.cancel_button)

        progressBar.isIndeterminate = true
        statusText.text = "Starting..."

        cancelButton.setOnClickListener {
            val intent = Intent(requireContext(), FormatService::class.java)
            requireContext().stopService(intent)
            parentFragmentManager.popBackStack()
        }

        val serviceIntent = Intent(requireContext(), FormatService::class.java).apply {
            putExtra("drive_device_name", drive.deviceName)
            putExtra("drive_model", drive.model)
            putExtra("config", config)
        }
        androidx.core.content.ContextCompat.startForegroundService(requireContext(), serviceIntent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FormatService.FORMAT_PROGRESS_ACTION)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(progressReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(progressReceiver)
    }
}
