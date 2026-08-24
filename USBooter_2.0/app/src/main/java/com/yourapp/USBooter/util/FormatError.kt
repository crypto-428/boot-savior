package com.yourapp.USBooter.util

import org.json.JSONObject

/** The phase of the flash a failure happened in. */
enum class FormatStage(val id: String) {
    PREPARE("prepare"),
    PERMISSION("permission"),
    ISO("iso"),
    ANALYSE("analyse"),
    TABLE("table"),
    FILESYSTEM("filesystem"),
    BOOT("boot"),
    VERIFY("verify"),
    FINALIZE("finalize")
}

/**
 * A machine-readable failure description. The WebView turns [code] and [stage]
 * into a localized explanation plus suggested next actions, and shows
 * [technical] verbatim for bug reports.
 */
data class FormatError(
    val code: String,
    val stage: FormatStage,
    val technical: String,
    /** English fallback, used if the UI has no string for [code]. */
    val fallback: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("stage", stage.id)
        put("technical", technical)
        put("fallback", fallback)
    }
}

/**
 * Maps raw failures - thrown exceptions and the "Error: ..." status strings the
 * format engine emits - onto stable error codes so the UI can explain exactly
 * which step failed and what to do next.
 */
object FormatErrors {

    fun of(code: String, stage: FormatStage, technical: String, fallback: String) =
        FormatError(code, stage, technical, fallback)

    /** Classifies an exception thrown out of the format engine. */
    fun classify(t: Throwable, stageHint: FormatStage = FormatStage.FILESYSTEM): FormatError {
        val technical = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
        val m = (t.message ?: "").lowercase()
        val code = when {
            t is java.util.concurrent.CancellationException || m.contains("cancel") -> "E-CAN-01"
            m.contains("permission") -> "E-USB-03"
            m.contains("no longer connected") || m.contains("disconnect") ||
                m.contains("endpoint") || m.contains("bulk transfer") ||
                m.contains("no response") || m.contains("timed out") -> "E-USB-02"
            m.contains("could not open") || m.contains("claiminterface") -> "E-USB-04"
            m.contains("verif") || m.contains("mismatch") || m.contains("checksum") -> "E-USB-06"
            m.contains("windows files") || m.contains("install.wim") -> "E-WIN-01"
            m.contains("too large for fat32") -> "E-ISO-03"
            m.contains("iso") && (m.contains("read") || m.contains("open")) -> "E-ISO-01"
            m.contains("bundled asset") || m.contains("boot.img") || m.contains("core.img") -> "E-BOOT-02"
            m.contains("boot sector") || m.contains("bootloader") || m.contains("grub") -> "E-BOOT-01"
            m.contains("ntfs") -> NtfsCapability.CODE_UNAVAILABLE
            m.contains("space") || m.contains("does not fit") || m.contains("capacity") -> "E-CFG-02"
            t is java.io.IOException -> "E-USB-05"
            else -> "E-UNK-01"
        }
        return FormatError(code, stageOf(code, stageHint), technical, t.message ?: technical)
    }

    /**
     * Classifies a failure the engine reported through its progress callback,
     * where all we have is the human-readable status + details.
     */
    fun classifyMessage(status: String, details: String): FormatError {
        val text = "$status $details".lowercase()
        val code = when {
            text.contains("cancel") -> "E-CAN-01"
            text.contains("windows files") || text.contains("install.wim") ||
                text.contains("no installation media") -> "E-WIN-01"
            text.contains("permission") -> "E-USB-03"
            text.contains("no drive") || text.contains("no longer connected") -> "E-USB-02"
            text.contains("could not open") -> "E-USB-04"
            text.contains("partition layout could not be read") ||
                text.contains("invalid configuration") -> "E-CFG-01"
            text.contains("can no longer be read") || text.contains("dropped the permission") -> "E-ISO-01"
            text.contains("cannot boot on legacy bios") || text.contains("no hybrid boot record") -> "E-ISO-02"
            text.contains("too large for fat32") -> "E-ISO-03"
            text.contains("ntfs") -> NtfsCapability.CODE_UNAVAILABLE
            text.contains("verif") || text.contains("mismatch") -> "E-USB-06"
            text.contains("bundled asset") || text.contains("boot.img") ||
                text.contains("core.img") -> "E-BOOT-02"
            text.contains("grub") || text.contains("bootloader") ||
                text.contains("boot sector") -> "E-BOOT-01"
            text.contains("space") || text.contains("does not fit") -> "E-CFG-02"
            text.contains("write") || text.contains("i/o") -> "E-USB-05"
            else -> "E-UNK-01"
        }
        val cleaned = status.removePrefix("Error: ").removePrefix("error: ")
        return FormatError(
            code,
            stageOf(code, FormatStage.FILESYSTEM),
            listOf(cleaned, details).filter { it.isNotBlank() }.joinToString(" - "),
            cleaned
        )
    }

    private fun stageOf(code: String, fallback: FormatStage): FormatStage = when (code) {
        "E-USB-03" -> FormatStage.PERMISSION
        "E-USB-02", "E-USB-04", "E-CFG-01", "E-CFG-02" -> FormatStage.PREPARE
        "E-ISO-01" -> FormatStage.ISO
        "E-ISO-02" -> FormatStage.ANALYSE
        "E-ISO-03", "E-BOOT-01", "E-BOOT-02" -> FormatStage.BOOT
        "E-USB-06", "E-WIN-01" -> FormatStage.VERIFY
        NtfsCapability.CODE_UNAVAILABLE -> FormatStage.FILESYSTEM
        else -> fallback
    }
}
