package com.yourapp.USBooter.util

import org.json.JSONObject

/**
 * Live view of what the drive is doing right now, in LBA terms.
 *
 * Every SCSI READ(10)/WRITE(10) chunk registers the range it touched here, so
 * the UI can show "writing LBA 2048-2175" while a copy runs instead of a bare
 * percentage. Verifiers additionally mark ranges as verified or failed, which
 * is what makes a failure report point at the exact sectors that disagreed.
 *
 * Purely observational: nothing in the write path depends on it, and it never
 * throws.
 */
object IoMonitor {

    data class Range(val op: String, val firstLba: Long, val lastLba: Long, val detail: String) {
        val text: String
            get() = if (firstLba == lastLba) "$op LBA $firstLba" else "$op LBA $firstLba–$lastLba" +
                (if (detail.isBlank()) "" else " · $detail")
    }

    @Volatile private var lastRead: Range? = null
    @Volatile private var lastWrite: Range? = null
    @Volatile private var lastVerified: Range? = null
    @Volatile private var lastFailed: Range? = null

    @Volatile private var readBlocks = 0L
    @Volatile private var writtenBlocks = 0L

    fun reset() {
        lastRead = null; lastWrite = null; lastVerified = null; lastFailed = null
        readBlocks = 0L; writtenBlocks = 0L
    }

    fun read(lba: Long, count: Int) {
        lastRead = Range("read", lba, lba + count - 1, "")
        readBlocks += count
    }

    fun write(lba: Long, count: Int) {
        lastWrite = Range("write", lba, lba + count - 1, "")
        writtenBlocks += count
    }

    /** A region that was re-read and matched what should be there. */
    fun verified(firstLba: Long, lastLba: Long, detail: String = "") {
        lastVerified = Range("verified", firstLba, lastLba, detail)
    }

    /** A region that did not match, or that the drive refused. */
    fun failed(firstLba: Long, lastLba: Long, detail: String = "") {
        lastFailed = Range("failed", firstLba, lastLba, detail)
        FlashReport.step("I/O failure region", Range("failed", firstLba, lastLba, detail).text)
    }

    /** Compact snapshot for the progress broadcast. */
    fun snapshot(): JSONObject = JSONObject().apply {
        lastRead?.let { put("read", it.text) }
        lastWrite?.let { put("write", it.text) }
        lastVerified?.let { put("verified", it.text) }
        lastFailed?.let { put("failed", it.text) }
        put("readBlocks", readBlocks)
        put("writtenBlocks", writtenBlocks)
    }

    /** One-line human summary, used in the exported report. */
    fun summary(): String = listOfNotNull(
        lastWrite?.text, lastRead?.text, lastVerified?.text, lastFailed?.text
    ).joinToString(" · ")
}
