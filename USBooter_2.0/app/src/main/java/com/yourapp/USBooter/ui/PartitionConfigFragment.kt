package com.yourapp.USBooter.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.textview.MaterialTextView
import com.yourapp.USBooter.R
import com.yourapp.USBooter.util.Filesystem
import com.yourapp.USBooter.util.LayoutConfig
import com.yourapp.USBooter.util.PartitionDefinition
import com.yourapp.USBooter.util.UsbDrive

class PartitionConfigFragment : Fragment() {

    private lateinit var drive: UsbDrive
    private lateinit var recyclerView: RecyclerView
    private lateinit var addPartitionButton: MaterialButton
    private lateinit var presetGroup: MaterialButtonToggleGroup
    private lateinit var tableTypeGroup: MaterialButtonToggleGroup
    private lateinit var diskMapBar: android.widget.LinearLayout
    private lateinit var totalInfoText: MaterialTextView
    private lateinit var continueButton: MaterialButton
    
    private val partitions = mutableListOf<PartitionDefinition>()
    private var tableType = com.yourapp.USBooter.util.PartitionTableType.MBR
    private var nextId = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drive = arguments?.getSerializable("drive") as UsbDrive
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_partition_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.partition_list)
        addPartitionButton = view.findViewById(R.id.add_partition_button)
        presetGroup = view.findViewById(R.id.preset_group)
        tableTypeGroup = view.findViewById(R.id.table_type_group)
        diskMapBar = view.findViewById(R.id.disk_map_bar)
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.title =
            "Partition Layout · ${drive.sizeHuman}"
        totalInfoText = view.findViewById(R.id.total_info)
        continueButton = view.findViewById(R.id.continue_button)

        tableTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                tableType = if (checkedId == R.id.table_type_gpt)
                    com.yourapp.USBooter.util.PartitionTableType.GPT
                else
                    com.yourapp.USBooter.util.PartitionTableType.MBR
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        val adapter = PartitionAdapter(partitions, 
            onEdit = { position -> showEditDialog(position) },
            onDelete = { position -> deletePartition(position) }
        )
        recyclerView.adapter = adapter

        // Swipe to delete
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                deletePartition(viewHolder.adapterPosition)
            }
        }).attachToRecyclerView(recyclerView)

        addPartitionButton.setOnClickListener { showAddDialog() }
        continueButton.setOnClickListener { validateAndProceed() }

        // Preset buttons
        presetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.preset_multiboot -> applyPreset(LayoutConfig.multibootPreset())
                    R.id.preset_windows -> applyPreset(LayoutConfig.singlePartitionPreset(Filesystem.FAT32))
                    R.id.preset_single_exfat -> applyPreset(LayoutConfig.singlePartitionPreset(Filesystem.EXFAT))
                }
                adapter.notifyDataSetChanged()
                updateTotalInfo()
            }
        }

        // Load default preset
        applyPreset(LayoutConfig.multibootPreset())
        adapter.notifyDataSetChanged()
        updateTotalInfo()
    }

    private fun applyPreset(config: LayoutConfig) {
        partitions.clear()
        nextId = 1
        config.partitions.forEach { def ->
            partitions.add(def.copy(id = nextId++))
        }
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_partition_edit, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.partition_label)
        val sizeInput = dialogView.findViewById<EditText>(R.id.partition_size)
        val filesystemSpinner = dialogView.findViewById<Spinner>(R.id.filesystem_spinner)
        val fillSwitch = dialogView.findViewById<SwitchMaterial>(R.id.fill_remaining_switch)
        val espSwitch = dialogView.findViewById<SwitchMaterial>(R.id.esp_switch)
        val fillSizeHint = dialogView.findViewById<MaterialTextView>(R.id.fill_size_hint)

        filesystemSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            Filesystem.entries.map { it.displayName }
        )

        // Show/hide size input based on fill switch
        fillSwitch.setOnCheckedChangeListener { _, isChecked ->
            sizeInput.visibility = if (isChecked) View.GONE else View.VISIBLE
            fillSizeHint.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            // If filling remaining, remove any existing fill partition
            if (isChecked && partitions.any { it.sizeMB == -1 }) {
                Toast.makeText(requireContext(), 
                    "Only one partition can fill remaining space", Toast.LENGTH_SHORT).show()
                fillSwitch.isChecked = false
            }
        }

        // ESP only makes sense for FAT32
        filesystemSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fs = Filesystem.entries[position]
                espSwitch.isEnabled = fs == Filesystem.FAT32
                if (!espSwitch.isEnabled) espSwitch.isChecked = false
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Add Partition")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val label = labelInput.text.toString().ifEmpty { "PART${nextId}" }
                val sizeMB = if (fillSwitch.isChecked) -1 else (sizeInput.text.toString().toIntOrNull() ?: 100)
                val filesystem = Filesystem.entries[filesystemSpinner.selectedItemPosition]
                val isESP = espSwitch.isChecked

                // Validate
                if (sizeMB == -1 && partitions.any { it.sizeMB == -1 }) {
                    Toast.makeText(requireContext(), 
                        "Only one partition can fill remaining space", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (sizeMB > 0 && sizeMB < 10) {
                    Toast.makeText(requireContext(), 
                        "Minimum partition size is 10MB", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                partitions.add(
                    PartitionDefinition(
                        id = nextId++,
                        label = label,
                        sizeMB = sizeMB,
                        filesystem = filesystem,
                        isESP = isESP
                    )
                )
                recyclerView.adapter?.notifyItemInserted(partitions.size - 1)
                updateTotalInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(position: Int) {
        val partition = partitions[position]
        val dialogView = layoutInflater.inflate(R.layout.dialog_partition_edit, null)
        val labelInput = dialogView.findViewById<EditText>(R.id.partition_label)
        val sizeInput = dialogView.findViewById<EditText>(R.id.partition_size)
        val filesystemSpinner = dialogView.findViewById<Spinner>(R.id.filesystem_spinner)
        val fillSwitch = dialogView.findViewById<SwitchMaterial>(R.id.fill_remaining_switch)
        val espSwitch = dialogView.findViewById<SwitchMaterial>(R.id.esp_switch)

        filesystemSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            Filesystem.entries.map { it.displayName }
        )

        // Pre-fill
        labelInput.setText(partition.label)
        if (partition.sizeMB == -1) {
            fillSwitch.isChecked = true
            sizeInput.visibility = View.GONE
        } else {
            sizeInput.setText(partition.sizeMB.toString())
        }
        filesystemSpinner.setSelection(Filesystem.entries.indexOf(partition.filesystem))
        espSwitch.isChecked = partition.isESP

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Partition")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sizeMB = if (fillSwitch.isChecked) -1 else (sizeInput.text.toString().toIntOrNull() ?: 100)
                
                // Check if we're setting fill while another partition already has it
                if (sizeMB == -1 && partitions.any { it.id != partition.id && it.sizeMB == -1 }) {
                    Toast.makeText(requireContext(), 
                        "Only one partition can fill remaining space", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                partitions[position] = partition.copy(
                    label = labelInput.text.toString().ifEmpty { partition.label },
                    sizeMB = sizeMB,
                    filesystem = Filesystem.entries[filesystemSpinner.selectedItemPosition],
                    isESP = espSwitch.isChecked
                )
                recyclerView.adapter?.notifyItemChanged(position)
                updateTotalInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePartition(position: Int) {
        if (partitions.size <= 1) {
            Toast.makeText(requireContext(), 
                "Must have at least one partition", Toast.LENGTH_SHORT).show()
            recyclerView.adapter?.notifyItemChanged(position)
            return
        }
        partitions.removeAt(position)
        recyclerView.adapter?.notifyItemRemoved(position)
        updateTotalInfo()
    }

    private fun validateAndProceed() {
        if (partitions.isEmpty()) {
            Toast.makeText(requireContext(), "Add at least one partition", Toast.LENGTH_SHORT).show()
            return
        }

        if (!partitions.any { it.sizeMB == -1 }) {
            Toast.makeText(requireContext(), 
                "One partition must fill remaining space (or total size may not match drive)", 
                Toast.LENGTH_LONG).show()
        }

        val config = LayoutConfig(partitions.toList(), tableType)

        val errors = config.validate()
        if (errors.isNotEmpty()) {
            Toast.makeText(requireContext(), errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            return
        }
        
        // Navigate to confirmation
        val fragment = ConfirmationFragment().apply {
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

    private fun updateTotalInfo() {
        val fixedMB = partitions.filter { it.sizeMB > 0 }.sumOf { it.sizeMB }
        val fillCount = partitions.count { it.sizeMB == -1 }
        val remainingMB = (drive.sizeBytes / 1_000_000) - fixedMB

        DiskMapView.populate(diskMapBar, partitions, drive.sizeBytes)
        
        totalInfoText.text = buildString {
            append("Partitions: ${partitions.size}\n")
            append("Fixed size: ${fixedMB}MB\n")
            if (fillCount > 0) {
                append("Remaining: ${if (remainingMB > 0) "${remainingMB}MB" else "Calculating..."}\n")
            }
            append("Drive: ${drive.sizeHuman}")
        }
    }
}

class PartitionAdapter(
    private val partitions: List<PartitionDefinition>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PartitionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val labelText: MaterialTextView = view.findViewById(R.id.partition_label_text)
        val detailsText: MaterialTextView = view.findViewById(R.id.partition_details_text)
        val editButton: MaterialButton = view.findViewById(R.id.edit_partition_button)
        val espChip: Chip = view.findViewById(R.id.esp_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_partition_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val partition = partitions[position]
        holder.labelText.text = partition.label
        holder.detailsText.text = buildString {
            append(partition.filesystem.displayName)
            append(" | ")
            if (partition.sizeMB == -1) {
                append("Fill remaining")
            } else {
                append("${partition.sizeMB}MB")
            }
        }
        holder.espChip.visibility = if (partition.isESP) View.VISIBLE else View.GONE
        holder.editButton.setOnClickListener { onEdit(position) }
        holder.itemView.setOnClickListener { onEdit(position) }
    }

    override fun getItemCount() = partitions.size
}