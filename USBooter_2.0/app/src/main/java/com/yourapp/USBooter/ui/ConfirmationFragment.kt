package com.yourapp.USBooter.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.yourapp.USBooter.R
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.UsbDrive

class ConfirmationFragment : Fragment() {

    private lateinit var drive: UsbDrive
    private lateinit var config: LayoutConfig

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
        return inflater.inflate(R.layout.fragment_confirmation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val driveInfoText = view.findViewById<MaterialTextView>(R.id.drive_info_text)
        val diskMapBar = view.findViewById<android.widget.LinearLayout>(R.id.disk_map_bar)
        val wipeEditText = view.findViewById<EditText>(R.id.wipe_confirmation_input)
        val formatButton = view.findViewById<MaterialButton>(R.id.format_button)
        val cancelButton = view.findViewById<MaterialButton>(R.id.cancel_button)

        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title = "Confirm · Irreversible"

        DiskMapView.populate(diskMapBar, config.partitions, drive.sizeBytes)

        // Display drive info
        driveInfoText.text = """
            USB Port: ${drive.deviceName}
            Model: ${drive.model}
            Capacity: ${drive.sizeHuman}
            Table: ${config.tableType.displayName}
        """.trimIndent()

        // Enable format button only when user types WIPE
        formatButton.isEnabled = false
        wipeEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                formatButton.isEnabled = s?.toString()?.uppercase() == "WIPE"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        formatButton.setOnClickListener {
            navigateToFormatProgress()
        }

        cancelButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun navigateToFormatProgress() {
        val fragment = FormatProgressFragment().apply {
            arguments = Bundle().apply {
                putSerializable("drive", drive)
                putSerializable("config", config)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}