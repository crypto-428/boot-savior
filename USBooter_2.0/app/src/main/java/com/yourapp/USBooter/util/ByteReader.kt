package com.yourapp.USBooter.util

/**
 * Random-access, read-only byte source. Implemented both by the selected ISO
 * ([IsoSource]) and by the finished pendrive ([DeviceByteReader]), so the same
 * ISO9660 parser can walk the image *and* the drive it was written to. That is
 * what makes a real post-flash content check possible: the file tree is read
 * back from the stick exactly the way a bootloader would see it.
 */
interface ByteReader {
    val size: Long
    fun readAt(offset: Long, length: Int): ByteArray
}

/** Reads arbitrary byte ranges from the raw USB block device. */
class DeviceByteReader(
    private val device: UsbBulkStorageDevice,
    private val startLba: Long = 0,
    override val size: Long = device.totalBlocks * device.blockSize
) : ByteReader {

    override fun readAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        val out = ByteArray(length)
        if (length == 0) return out
        val blockSize = device.blockSize
        val firstLba = startLba + offset / blockSize
        val skew = (offset % blockSize).toInt()
        val blocks = ((skew + length) + blockSize - 1) / blockSize
        val raw = device.readBlocks(firstLba, blocks)
        val available = minOf(length, raw.size - skew)
        if (available > 0) System.arraycopy(raw, skew, out, 0, available)
        return out
    }
}
