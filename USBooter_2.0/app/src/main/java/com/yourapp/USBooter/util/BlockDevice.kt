package com.yourapp.USBooter.util

/**
 * A readable/writable block target: everything the repair engine and the
 * verifiers need from a drive, without depending on USB at all.
 *
 * [UsbBulkStorageDevice] is the real implementation; unit tests supply an
 * in-memory one, which is what makes the repair logic testable without a
 * physical pendrive.
 */
interface BlockDevice : BlockWriter {
    /** Total number of addressable logical blocks. */
    val totalBlocks: Long

    /** Reads [count] blocks starting at logical block [lba]. */
    fun readBlocks(lba: Long, count: Int): ByteArray

    /** Flushes any device-side write cache. */
    fun synchronizeCache()
}
