package com.yourapp.USBooter.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects everything a support ticket needs about one flash: the drive, the
 * image, the resolved plan, every progress step with a timestamp, the checksums
 * of the bundled bootloader assets, and any read-back mismatch with the exact
 * LBA and byte offset where the drive disagreed with what was written.
 *
 * A single process-wide instance is used because the flash runs in a foreground
 * service while the UI lives in a WebView; the UI asks for the report through
 * the JS bridge once the run is over.
 */
object FlashReport {

    data class Mismatch(val where: String, val lba: Long, val byteOffset: Long, val expected: String, val actual: String)

    private val steps = mutableListOf<String>()
    private val checksums = LinkedHashMap<String, String>()
    private val mismatches = mutableListOf<Mismatch>()
    private val facts = LinkedHashMap<String, String>()

    @Volatile
    var title: String = "USBooter flash report"
        private set

    @Volatile
    var dryRun: Boolean = false

    @Volatile
    var outcome: String = "not started"

    private val stamp: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    fun start(title: String, dryRun: Boolean) {
        steps.clear(); checksums.clear(); mismatches.clear(); facts.clear()
        this.title = title
        this.dryRun = dryRun
        outcome = "running"
        step("Report started", SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
    }

    @Synchronized
    fun fact(key: String, value: String) { facts[key] = value }

    @Synchronized
    fun step(status: String, detail: String = "") {
        val line = if (detail.isBlank()) "[$stamp] $status" else "[$stamp] $status — $detail"
        if (steps.lastOrNull()?.substringAfter("] ") == line.substringAfter("] ")) return
        steps.add(line)
        if (steps.size > 2000) steps.removeAt(0)
    }

    @Synchronized
    fun checksum(asset: String, sha256: String) { checksums[asset] = sha256 }

    @Synchronized
    fun mismatch(where: String, lba: Long, byteOffset: Long, expected: String, actual: String) {
        mismatches.add(Mismatch(where, lba, byteOffset, expected, actual))
        step("Read-back mismatch", "$where at LBA $lba, byte $byteOffset: expected $expected, read $actual")
    }

    @Synchronized
    fun finish(outcome: String) {
        this.outcome = outcome
        step("Finished", outcome)
    }

    @Synchronized
    fun hasContent(): Boolean = steps.isNotEmpty()

    /** Plain-text report, ready to be shared or saved. */
    @Synchronized
    fun render(): String = buildString {
        appendLine("=== $title ===")
        appendLine("Mode: ${if (dryRun) "DRY RUN (nothing was written)" else "Live flash"}")
        appendLine("Outcome: $outcome")
        appendLine()
        if (facts.isNotEmpty()) {
            appendLine("--- Configuration ---")
            facts.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
        }
        appendLine("--- Checksums ---")
        if (checksums.isEmpty()) appendLine("(none recorded)")
        checksums.forEach { (k, v) -> appendLine("$k  SHA-256 $v") }
        appendLine()
        appendLine("--- Read-back mismatches ---")
        if (mismatches.isEmpty()) appendLine("(none — every verified region matched)")
        mismatches.forEach {
            appendLine("${it.where}: LBA ${it.lba}, byte offset ${it.byteOffset}, expected ${it.expected}, read ${it.actual}")
        }
        appendLine()
        appendLine("--- Step log ---")
        steps.forEach { appendLine(it) }
    }
}
