package com.yourapp.USBooter.util

/**
 * In-memory drive used by the repair tests.
 *
 * Sparse on purpose: only the sectors a test actually writes are stored, so a
 * 10 GB "drive" costs a few kilobytes and every branch of the repair engine can
 * be exercised without a physical pendrive.
 */
class FakeBlockDevice(
    override val totalBlocks: Long,
    override val blockSize: Int = 512,
    private val failCacheFlush: Boolean = false
) : BlockDevice {

    private val sectors = HashMap<Long, ByteArray>()
    var cacheFlushes = 0
        private set

    override fun readBlocks(lba: Long, count: Int): ByteArray {
        require(lba >= 0 && count >= 1) { "bad read at $lba ($count)" }
        require(lba + count <= totalBlocks) { "read past end of drive at $lba" }
        val out = ByteArray(count * blockSize)
        for (i in 0 until count) {
            sectors[lba + i]?.let { System.arraycopy(it, 0, out, i * blockSize, blockSize) }
        }
        return out
    }

    override fun writeBlocks(lba: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "unaligned write of ${data.size} bytes" }
        require(lba >= 0 && lba + data.size / blockSize <= totalBlocks) { "write past end of drive at $lba" }
        for (i in 0 until data.size / blockSize) {
            sectors[lba + i] = data.copyOfRange(i * blockSize, (i + 1) * blockSize)
        }
    }

    override fun synchronizeCache() {
        cacheFlushes++
        if (failCacheFlush) throw java.io.IOException("SCSI SYNCHRONIZE CACHE unsupported")
    }

    fun sector(lba: Long): ByteArray = readBlocks(lba, 1)

    fun put(lba: Long, sector: ByteArray) = writeBlocks(lba, sector.copyOf(blockSize))
}
