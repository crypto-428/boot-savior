package com.yourapp.USBooter.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Talks directly to a USB Mass Storage device using the SCSI command set over the
 * Bulk-Only Transport (BOT) protocol, via the standard Android USB Host API.
 *
 * This does NOT require root: it uses the same UsbManager/UsbDeviceConnection APIs
 * any Android app can use once the user grants USB permission for the device. It
 * talks to the device below the filesystem/partition level, which is exactly what's
 * needed to write a fresh partition table and filesystem from scratch.
 */
class UsbBulkStorageDevice private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : BlockWriter {
    override var blockSize: Int = 512
        private set

    var totalBlocks: Long = 0
        private set

    private val timeoutMs = 5000
    private val usbLock = Any()

    companion object {
        private const val MASS_STORAGE_CLASS = UsbConstants.USB_CLASS_MASS_STORAGE
        private const val SCSI_SUBCLASS = 6      // SCSI transparent command set
        private const val BULK_ONLY_PROTOCOL = 80 // Bulk-only transport

        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13

        private const val DIR_IN = 0x80
        private const val DIR_OUT = 0x00

        /**
         * Finds the mass-storage bulk-only interface on this device, opens a
         * connection (permission must already be granted), and claims it.
         * Returns null if this isn't a usable USB Mass Storage device.
         */
        fun open(usbManager: UsbManager, device: UsbDevice): UsbBulkStorageDevice? {
            val massStorageInterface = findMassStorageInterface(device) ?: return null

            val connection = usbManager.openDevice(device) ?: return null
            if (!connection.claimInterface(massStorageInterface, true)) {
                connection.close()
                return null
            }

            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until massStorageInterface.endpointCount) {
                val ep = massStorageInterface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
            }

            if (inEp == null || outEp == null) {
                connection.releaseInterface(massStorageInterface)
                connection.close()
                return null
            }

            val storage = UsbBulkStorageDevice(connection, massStorageInterface, inEp, outEp)
            return try {
                storage.initialize()
                storage
            } catch (e: IOException) {
                storage.close()
                null
            }
        }

        /**
         * True if this device exposes a USB Mass Storage / SCSI bulk-only interface
         * at all - used to filter the device list before requesting permission.
         */
        fun isMassStorageDevice(device: UsbDevice): Boolean {
            return findMassStorageInterface(device) != null
        }

        private fun findMassStorageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == MASS_STORAGE_CLASS &&
                    intf.interfaceSubclass == SCSI_SUBCLASS &&
                    intf.interfaceProtocol == BULK_ONLY_PROTOCOL
                ) {
                    return intf
                }
            }
            return null
        }
    }

    /** Sends TEST UNIT READY + READ CAPACITY(10) to learn block size and count. */
    private fun initialize() {
        // Some devices need a moment / a couple of retries after being opened.
        var ready = false
        repeat(5) {
            if (testUnitReady()) {
                ready = true
                return@repeat
            }
            Thread.sleep(200)
        }
        if (!ready) throw IOException("Device did not report ready (TEST UNIT READY failed)")

        val capacity = readCapacity10() ?: throw IOException("READ CAPACITY(10) failed")
        totalBlocks = capacity.first
        blockSize = capacity.second
    }

    fun testUnitReady(): Boolean {
        val cb = ByteArray(6) // all zero = TEST UNIT READY (opcode 0x00)
        return try {
            val status = executeCommand(cb, null, 0, DIR_IN)
            status == 0
        } catch (e: IOException) {
            false
        }
    }

    /** Sends SCSI START STOP UNIT with START bit = 0 (Stop) to safely power down media. */
    fun stopUnit() {
        val cb = ByteArray(6)
        cb[0] = 0x1B.toByte()
        cb[4] = 0x00.toByte() // START=0 (Stop)
        executeCommand(cb, null, 0, DIR_OUT)
    }

    /** Returns (totalBlocks, blockSizeBytes) or null on failure. */
    private fun readCapacity10(): Pair<Long, Int>? {
        val cb = ByteArray(10)
        cb[0] = 0x25 // READ CAPACITY(10) opcode
        val response = ByteArray(8)
        val status = executeCommand(cb, response, response.size, DIR_IN)
        if (status != 0) return null

        val buf = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.int.toLong() and 0xFFFFFFFFL
        val blockLen = buf.int
        return Pair(lastLba + 1, blockLen)
    }

    /**
     * Largest data stage we put in a single SCSI command. Plenty of cheap USB
     * bridges quietly truncate or stall on transfers bigger than 64 KiB, which
     * shows up later as a drive that was "written successfully" but does not
     * boot - so every read/write is split into chunks of at most this size.
     */
    private val maxTransferBytes: Int
        get() = 64 * 1024

    /** Reads [count] blocks starting at logical block address [lba]. */
    fun readBlocks(lba: Long, count: Int): ByteArray {
        require(count >= 1) { "count out of range" }
        val out = ByteArray(count * blockSize)
        val blocksPerChunk = (maxTransferBytes / blockSize).coerceAtLeast(1)
        var done = 0
        while (done < count) {
            val take = minOf(blocksPerChunk, count - done)
            readChunk(lba + done, take, out, done * blockSize)
            done += take
        }
        return out
    }

    private fun readChunk(lba: Long, count: Int, into: ByteArray, offset: Int) {
        val cb = ByteArray(10)
        cb[0] = 0x28 // READ(10)
        cb[2] = (lba shr 24).toByte()
        cb[3] = (lba shr 16).toByte()
        cb[4] = (lba shr 8).toByte()
        cb[5] = lba.toByte()
        cb[7] = (count shr 8).toByte()
        cb[8] = count.toByte()

        val chunk = ByteArray(count * blockSize)
        IoMonitor.read(lba, count)
        val status = executeCommand(cb, chunk, chunk.size, DIR_IN)
        if (status != 0) {
            IoMonitor.failed(lba, lba + count - 1, "READ(10) status=$status")
            throw IOException("READ(10) failed at LBA $lba, status=$status")
        }
        System.arraycopy(chunk, 0, into, offset, chunk.size)
    }

    /** Writes [data] (must be a multiple of blockSize) starting at logical block address [lba]. */
    override fun writeBlocks(lba: Long, data: ByteArray) {
        require(data.size % blockSize == 0) { "data size must be a multiple of blockSize" }
        val count = data.size / blockSize
        require(count >= 1) { "count out of range" }

        val blocksPerChunk = (maxTransferBytes / blockSize).coerceAtLeast(1)
        var done = 0
        while (done < count) {
            val take = minOf(blocksPerChunk, count - done)
            val chunk = data.copyOfRange(done * blockSize, (done + take) * blockSize)
            writeChunk(lba + done, chunk)
            done += take
        }
    }

    private fun writeChunk(lba: Long, data: ByteArray) {
        val count = data.size / blockSize
        val cb = ByteArray(10)
        cb[0] = 0x2A // WRITE(10)
        cb[2] = (lba shr 24).toByte()
        cb[3] = (lba shr 16).toByte()
        cb[4] = (lba shr 8).toByte()
        cb[5] = lba.toByte()
        cb[7] = (count shr 8).toByte()
        cb[8] = count.toByte()

        IoMonitor.write(lba, count)
        val status = executeCommand(cb, data, data.size, DIR_OUT)
        if (status != 0) {
            IoMonitor.failed(lba, lba + count - 1, "WRITE(10) status=$status")
            throw IOException("WRITE(10) failed at LBA $lba, status=$status")
        }
    }

    /** Writes zero-filled blocks - convenience helper used to wipe regions before formatting. */
    fun writeZeroBlocks(lba: Long, count: Int) {
        writeBlocks(lba, ByteArray(count * blockSize))
    }

    /**
     * SCSI SYNCHRONIZE CACHE(10). Completion is not reported until the device
     * confirms this command and becomes ready again. A bridge which rejects the
     * command is an error during flashing; silently ignoring it can produce a
     * drive that reaches 100% while its final sectors are still volatile.
     */
    fun synchronizeCache() {
        val cb = ByteArray(10)
        cb[0] = 0x35 // SYNCHRONIZE CACHE(10), LBA 0 + length 0 = whole medium
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                val status = executeCommand(cb, null, 0, DIR_OUT)
                if (status != 0) throw IOException("SYNCHRONIZE CACHE failed, status=$status")
                if (waitUntilReady()) return
                throw IOException("Drive did not become ready after cache synchronization")
            } catch (e: Exception) {
                lastFailure = e
                if (attempt < 2) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw IOException("Could not flush the USB drive", lastFailure)
    }

    private fun waitUntilReady(): Boolean {
        repeat(20) {
            if (testUnitReady()) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * SCSI REQUEST SENSE. After a CHECK CONDITION the device latches the error
     * and rejects further commands until the sense data is collected, so this is
     * issued automatically inside the command path.
     */
    private fun requestSense(): ByteArray? {
        val cb = ByteArray(6)
        cb[0] = 0x03
        cb[4] = 18
        val sense = ByteArray(18)
        return try {
            if (executeCommandInternal(cb, sense, sense.size, DIR_IN) == 0) sense else null
        } catch (e: IOException) {
            null
        }
    }

    fun close() {
        // Flush the device cache before we let go of it.
        runCatching { synchronizeCache() }
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        connection.close()
    }

    // ── Bulk-Only Transport plumbing ─────────────────────────────────────

    /**
     * Sends one CBW, transfers the data stage (if any), then reads the CSW.
     * Returns the SCSI status byte (0 = success) from the CSW.
     */
    private fun executeCommand(
        commandBlock: ByteArray,
        data: ByteArray?,
        dataLength: Int,
        direction: Int
    ): Int = synchronized(usbLock) {
        var retries = 2
        var senseRetried = false
        while (retries >= 0) {
            try {
                val status = executeCommandInternal(commandBlock, data, dataLength, direction)
                // CHECK CONDITION: the device latches an error (very often just a
                // "medium may have changed" unit attention right after opening it)
                // and refuses everything else until the sense data is read.
                if (status == 1 && !senseRetried) {
                    senseRetried = true
                    requestSense()
                    continue
                }
                return status
            } catch (e: IOException) {
                if (retries == 0) throw e
                retries--
                clearHalt(inEndpoint)
                clearHalt(outEndpoint)
                Thread.sleep(100)
            }
        }
        throw IOException("Failed after retries")
    }

    private fun executeCommandInternal(
        commandBlock: ByteArray,
        data: ByteArray?,
        dataLength: Int,
        direction: Int
    ): Int {
        val tag = Random.nextInt()
        val cbw = buildCbw(tag, dataLength, direction, commandBlock)

        val cbwSent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, timeoutMs)
        if (cbwSent != cbw.size) {
            throw IOException("Failed to send command (CBW): sent $cbwSent of ${cbw.size}")
        }

        if (dataLength > 0 && data != null) {
            var transferred = 0
            while (transferred < dataLength) {
                val toTransfer = minOf(dataLength - transferred, 16384)
                val ep = if (direction == DIR_IN) inEndpoint else outEndpoint
                val result = connection.bulkTransfer(ep, data, transferred, toTransfer, timeoutMs)
                if (result < 0) {
                    throw IOException("Failed to ${if (direction == DIR_IN) "read" else "write"} data stage at offset $transferred (expected $toTransfer)")
                }
                transferred += result
                if (result == 0) break
            }
            if (transferred < dataLength) {
                throw IOException("Incomplete data stage transfer: $transferred/$dataLength")
            }
        }

        val csw = ByteArray(CSW_LENGTH)
        val cswReceived = connection.bulkTransfer(inEndpoint, csw, CSW_LENGTH, timeoutMs)
        if (cswReceived != CSW_LENGTH) {
            throw IOException("Failed to read status (CSW): received $cswReceived of $CSW_LENGTH")
        }

        val buf = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buf.int
        val cswTag = buf.int
        if (cswTag != tag) throw IOException("CSW tag mismatch - the device fell out of sync")
        val residue = buf.int.toLong() and 0xFFFFFFFFL
        val status = buf.get().toInt() and 0xFF

        if (signature != CSW_SIGNATURE) throw IOException("Bad CSW signature: ${String.format("0x%08X", signature)}")
        if (status == 0 && residue != 0L) {
            throw IOException("Incomplete SCSI transfer: device reports $residue unwritten/unread bytes")
        }
        return status
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        // USB standard request: CLEAR_FEATURE (ENDPOINT_HALT)
        connection.controlTransfer(
            0x02, // Endpoint recipient
            0x01, // CLEAR_FEATURE
            0x00, // ENDPOINT_HALT
            endpoint.address,
            null, 0, timeoutMs
        )
    }

    private fun buildCbw(tag: Int, dataLength: Int, direction: Int, commandBlock: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CBW_SIGNATURE)
        buf.putInt(tag)
        buf.putInt(dataLength)
        buf.put(direction.toByte())
        buf.put(0) // LUN 0
        buf.put(commandBlock.size.toByte())
        buf.put(commandBlock)
        // pad command block field out to 16 bytes total
        repeat(16 - commandBlock.size) { buf.put(0) }
        return buf.array()
    }
}
