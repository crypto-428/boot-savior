package com.yourapp.USBooter.util

/**
 * The minimum a filesystem formatter needs from a block target: a sector size and
 * the ability to write whole sectors at an LBA.
 *
 * Extracted so formatters can be exercised against an in-memory target (see
 * [NtfsCapability]) instead of only a real USB drive.
 */
interface BlockWriter {
    val blockSize: Int

    /** Writes [data] (a multiple of [blockSize]) starting at logical block [lba]. */
    fun writeBlocks(lba: Long, data: ByteArray)
}
