package com.yourapp.USBooter.util

import org.json.JSONArray

/**
 * Records *why* the copy step did what it did, so a flash that ends with an
 * empty pendrive can be explained instead of guessed at.
 *
 * Every entry is a short label plus a value, in the order they happened: which
 * directory tree was used (UDF or ISO9660, and whether the other one existed),
 * how many entries and files were parsed, the largest file, which layout was
 * chosen, and how many files/bytes actually landed on each partition. The UI
 * shows the list in the failure report panel, and it is mirrored into the
 * exportable flash report.
 */
object CopyDiagnostics {

    private val entries = mutableListOf<Pair<String, String>>()

    fun reset() = synchronized(entries) { entries.clear() }

    fun record(label: String, value: String) {
        synchronized(entries) {
            entries.removeAll { it.first == label }
            entries.add(label to value)
        }
        FlashReport.step("Diagnostics: $label", value)
    }

    fun lines(): List<String> = synchronized(entries) { entries.map { "${it.first}: ${it.second}" } }

    fun toJson(): JSONArray = JSONArray().also { array -> lines().forEach { array.put(it) } }

    /** One-line summary used when there is no room for the full list. */
    fun summary(): String = lines().joinToString(" · ")
}
