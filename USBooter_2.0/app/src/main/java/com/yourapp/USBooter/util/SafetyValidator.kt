package com.yourapp.USBooter.util

data class ValidationCheck(
    val description: String,
    val passed: Boolean
)

data class ValidationResult(
    val isSafe: Boolean,
    val checks: List<ValidationCheck>
)

/**
 * Sanity checks before formatting. Note that most of the old checks here (is
 * this internal eMMC, is this a system partition, etc.) are no longer needed:
 * the Android USB Host API structurally can only ever see devices actually
 * plugged in over USB - it has no path to internal storage or system
 * partitions at all, unlike shelling out to a root block device.
 */
class SafetyValidator {

    fun validateDriveSelection(drive: UsbDrive): ValidationResult {
        val checks = mutableListOf<ValidationCheck>()

        checks.add(
            ValidationCheck(
                "Drive size is reasonable for a USB drive (under 2TB)",
                drive.sizeBytes in 1..2_000_000_000_000L
            )
        )

        checks.add(
            ValidationCheck(
                "Drive reported a valid block size",
                drive.blockSize in 512..8192
            )
        )

        return ValidationResult(
            isSafe = checks.all { it.passed },
            checks = checks
        )
    }
}
