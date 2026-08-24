package com.yourapp.USBooter.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash/interrupt journal for a flash.
 *
 * Every stage the engine is about to perform is recorded *before* it runs and
 * marked done after it finishes, so if the user hits Stop, the cable is pulled
 * or the app is killed, the next launch knows exactly how far the drive got:
 *
 *  - nothing written yet          -> the drive is untouched, just start again
 *  - the table/filesystems exist  -> the copy can be resumed, files already
 *                                    verified in this journal are skipped
 *  - stopped mid-table            -> only a rollback (wipe the first 10 MB, so
 *                                    no half-valid partition table survives) is
 *                                    safe
 *
 * The journal lives in the app's private storage; it never writes to the stick.
 */
object FlashJournal {

    /** Coarse phases, ordered: each one implies the previous ones completed. */
    enum class Stage {
        NONE, WIPED, TABLE_WRITTEN, FILESYSTEMS_READY, COPYING, COPY_DONE,
        BOOTLOADER_INSTALLED, VERIFIED, COMPLETE
    }

    data class State(
        val deviceName: String,
        val driveModel: String,
        val isoName: String,
        val isoUri: String,
        val mode: String,
        val stage: Stage,
        val bootStartLba: Long,
        val interrupted: Boolean,
        val copiedFiles: List<String>,
        val updatedAt: Long
    ) {
        /** A partially written table is the only state that must not be resumed. */
        val resumable: Boolean
            get() = interrupted && stage >= Stage.FILESYSTEMS_READY && stage < Stage.COMPLETE

        /** Nothing was written yet, so there is nothing to roll back either. */
        val untouched: Boolean get() = stage == Stage.NONE

        val needsRollback: Boolean
            get() = interrupted && stage in listOf(Stage.WIPED, Stage.TABLE_WRITTEN)

        fun describe(): String = when {
            !interrupted && stage == Stage.COMPLETE -> "The last flash finished normally."
            resumable -> "The last flash of $isoName stopped after ${stage.name.lowercase().replace('_', ' ')}; " +
                "${copiedFiles.size} file(s) were already written and verified, so it can be resumed."
            needsRollback -> "The last flash stopped while the partition table was being written. " +
                "Roll the drive back before using it again."
            interrupted -> "The last flash of $isoName was interrupted at ${stage.name.lowercase().replace('_', ' ')}."
            else -> "No interrupted flash."
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("deviceName", deviceName)
            put("driveModel", driveModel)
            put("isoName", isoName)
            put("isoUri", isoUri)
            put("mode", mode)
            put("stage", stage.name)
            put("bootStartLba", bootStartLba)
            put("interrupted", interrupted)
            put("resumable", resumable)
            put("needsRollback", needsRollback)
            put("copiedFiles", JSONArray(copiedFiles))
            put("updatedAt", updatedAt)
            put("description", describe())
        }
    }

    private const val FILE_NAME = "flash-journal.json"

    @Volatile
    private var file: File? = null

    @Volatile
    private var current: State? = null

    /** Called once from the activity with `filesDir`. */
    fun attach(directory: File) {
        file = File(directory, FILE_NAME)
        current = load()
    }

    fun begin(deviceName: String, driveModel: String, isoName: String, isoUri: String, mode: String) {
        current = State(
            deviceName, driveModel, isoName, isoUri, mode,
            Stage.NONE, -1L, interrupted = true, copiedFiles = emptyList(),
            updatedAt = System.currentTimeMillis()
        )
        persist()
    }

    fun stage(stage: Stage, bootStartLba: Long = -1L) {
        val state = current ?: return
        if (stage.ordinal < state.stage.ordinal) return
        current = state.copy(
            stage = stage,
            bootStartLba = if (bootStartLba >= 0) bootStartLba else state.bootStartLba,
            updatedAt = System.currentTimeMillis()
        )
        persist()
        FlashReport.step("Journal", "stage = ${stage.name}")
    }

    /** Records a file whose bytes are on the drive and were read back correctly. */
    fun fileDone(path: String) {
        val state = current ?: return
        if (state.copiedFiles.contains(path)) return
        current = state.copy(copiedFiles = state.copiedFiles + path, updatedAt = System.currentTimeMillis())
        // Writing on every file would hammer the flash; persist in batches.
        if (current!!.copiedFiles.size % 25 == 0) persist()
    }

    /** The flash reached the end: the journal is closed and no longer offers a resume. */
    fun complete() {
        val state = current ?: return
        current = state.copy(stage = Stage.COMPLETE, interrupted = false, updatedAt = System.currentTimeMillis())
        persist()
    }

    /** The flash stopped early (user cancel, error, unplug). */
    fun interrupted(reason: String) {
        val state = current ?: return
        current = state.copy(interrupted = true, updatedAt = System.currentTimeMillis())
        persist()
        FlashReport.step("Journal", "interrupted at ${state.stage.name}: $reason")
    }

    fun clear() {
        current = null
        runCatching { file?.delete() }
    }

    /** The journal as the UI needs it, or null when there is nothing pending. */
    fun pending(): State? = current?.takeIf { it.interrupted && it.stage != Stage.COMPLETE }

    /** Files that are already on the drive and can be skipped on a resume. */
    fun alreadyCopied(): Set<String> = current?.copiedFiles?.toHashSet() ?: emptySet()

    private fun persist() {
        val target = file ?: return
        val state = current ?: return
        runCatching { target.writeText(state.toJson().toString()) }
    }

    private fun load(): State? {
        val target = file ?: return null
        if (!target.exists()) return null
        return runCatching {
            val json = JSONObject(target.readText())
            val files = json.optJSONArray("copiedFiles") ?: JSONArray()
            State(
                deviceName = json.optString("deviceName"),
                driveModel = json.optString("driveModel"),
                isoName = json.optString("isoName"),
                isoUri = json.optString("isoUri"),
                mode = json.optString("mode"),
                stage = runCatching { Stage.valueOf(json.optString("stage", "NONE")) }.getOrDefault(Stage.NONE),
                bootStartLba = json.optLong("bootStartLba", -1L),
                interrupted = json.optBoolean("interrupted", false),
                copiedFiles = (0 until files.length()).map { files.optString(it) },
                updatedAt = json.optLong("updatedAt", 0L)
            )
        }.getOrNull()
    }

    /**
     * Rollback: leaves the stick in a clean, predictable state by removing the
     * partition table and every filesystem signature in the first 10 MB (and the
     * GPT backup header at the end). No firmware or OS then sees a half-written
     * layout, and the drive can simply be formatted again.
     */
    fun rollback(device: UsbBulkStorageDevice, progress: (Int, String) -> Unit = { _, _ -> }): String {
        val blockSize = device.blockSize
        val head = (10L * 1024 * 1024 / blockSize).coerceAtMost(device.totalBlocks)
        var lba = 0L
        while (lba < head) {
            val count = minOf(512L, head - lba).toInt()
            device.writeZeroBlocks(lba, count)
            lba += count
            progress(((lba * 90) / head).toInt(), "Clearing LBA 0-$lba")
        }
        // GPT keeps a backup table in the last 33 sectors; leave nothing behind.
        val tailStart = (device.totalBlocks - 34).coerceAtLeast(0)
        if (tailStart > head) {
            device.writeZeroBlocks(tailStart, (device.totalBlocks - tailStart).toInt())
            progress(95, "Clearing the GPT backup at LBA $tailStart")
        }
        device.synchronizeCache()
        clear()
        progress(100, "Drive rolled back")
        return "Cleared LBA 0-$head and the backup table at LBA $tailStart; the drive has no partitions now."
    }
}
