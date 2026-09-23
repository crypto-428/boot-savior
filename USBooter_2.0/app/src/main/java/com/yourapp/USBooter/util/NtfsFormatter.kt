package com.yourapp.USBooter.util

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Writes a fresh, empty NTFS filesystem directly to a partition.
 */
object NtfsFormatter {

    private const val MFT_RECORD_SIZE = 1024
    private const val INDEX_BLOCK_SIZE = 4096
    private const val MIN_VOLUME_BYTES = 16L * 1024 * 1024

    fun format(
        device: BlockWriter,
        partitionStartLba: Long,
        partitionSectorCount: Long,
        volumeLabel: String
    ) {

        val bytesPerSector = device.blockSize
        require(bytesPerSector == 512 || bytesPerSector == 4096) {
            "NTFS requires 512-byte or 4096-byte logical sectors (drive reports $bytesPerSector)"
        }
        require(partitionSectorCount * bytesPerSector >= MIN_VOLUME_BYTES) {
            "NTFS partition is too small; at least ${MIN_VOLUME_BYTES / (1024 * 1024)} MiB is required"
        }

        // Preferred path: stamp out a copy of a genuine Windows-made NTFS volume and
        // recompute only the size-dependent structures. It adapts to any partition
        // size and is what Windows itself accepts; the hand-built structures below
        // stay as a fallback for geometries the packed volume cannot serve.
        if (NtfsTemplate.supports(bytesPerSector, partitionSectorCount)) {
            NtfsTemplate.format(device, partitionStartLba, partitionSectorCount, volumeLabel)
            return
        }
        val sectorsPerCluster = maxOf(1, 4096 / bytesPerSector)
        val clusterSize = sectorsPerCluster * bytesPerSector
        val indexBlockSize = maxOf(INDEX_BLOCK_SIZE, clusterSize)
        val totalClusters = partitionSectorCount / sectorsPerCluster

        val mftLcn = 4L
        val mftMirrLcn = totalClusters / 2
        val serialNumber = System.currentTimeMillis()

        // ── 1. Boot Sector ────────
        val bootSector = buildBootSector(
            bytesPerSector, sectorsPerCluster, partitionSectorCount,
            mftLcn, mftMirrLcn, serialNumber, partitionStartLba
        )
        writeSectorAligned(device, partitionStartLba, bootSector)
        writeSectorAligned(device, partitionStartLba + partitionSectorCount - 1, bootSector)

        // ── 2. Mandatory Data preparation ────────
        val upcaseData = buildUpCase()
        val attrDefData = buildAttrDefFull()
        val logSize = (2L * 1024 * 1024).coerceAtMost((totalClusters / 100 * clusterSize).coerceAtLeast(clusterSize.toLong()))
        val bitmapSize = (totalClusters + 7) / 8

        val mftClusters = (16L * MFT_RECORD_SIZE + clusterSize - 1) / clusterSize
        val attrDefLcn = mftLcn + mftClusters
        val attrDefClusters = (attrDefData.size + clusterSize - 1L) / clusterSize
        val logLcn = attrDefLcn + attrDefClusters
        val bitmapLcn = logLcn + (logSize + clusterSize - 1) / clusterSize
        val upcaseLcn = bitmapLcn + (bitmapSize + clusterSize - 1) / clusterSize
        val upcaseClusters = (upcaseData.size + clusterSize - 1L) / clusterSize
        val mirrorClusters = (4L * MFT_RECORD_SIZE + clusterSize - 1) / clusterSize
        require(upcaseLcn + upcaseClusters < mftMirrLcn && mftMirrLcn + mirrorClusters <= totalClusters) {
            "NTFS metadata does not fit in this partition"
        }

        // ── 3. MFT Construction ────────
        val mftData = ByteArray(16 * MFT_RECORD_SIZE)
        val builder = MftBuilder(mftData, MFT_RECORD_SIZE, bytesPerSector)
        
        builder.build(0) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$MFT", 0, false))
            attr(Attribute.DataNonResident(mftLcn, (16L * MFT_RECORD_SIZE + clusterSize - 1) / clusterSize, 16L * MFT_RECORD_SIZE, clusterSize))
        }
        builder.build(1) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$MFTMirr", 0, false))
            attr(Attribute.DataNonResident(mftMirrLcn, (4L * MFT_RECORD_SIZE + clusterSize - 1) / clusterSize, 4L * MFT_RECORD_SIZE, clusterSize))
        }
        builder.build(2) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$LogFile", 0, false))
            attr(Attribute.DataNonResident(logLcn, (logSize + clusterSize - 1) / clusterSize, logSize, clusterSize))
        }
        builder.build(3) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$Volume", 0, false))
            attr(Attribute.VolumeName(volumeLabel))
            attr(Attribute.VolumeInfo())
        }
        builder.build(4) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$AttrDef", 0, false))
            attr(Attribute.DataNonResident(attrDefLcn, attrDefClusters, attrDefData.size.toLong(), clusterSize))
        }
        builder.build(5) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(5, ".", 0, true))
            directory()
            attr(Attribute.IndexRoot(indexBlockSize))
        }
        builder.build(6) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$Bitmap", 0, false))
            attr(Attribute.DataNonResident(bitmapLcn, (bitmapSize + clusterSize - 1) / clusterSize, bitmapSize, clusterSize))
        }
        builder.build(7) {
            attr(Attribute.StandardInfo())
            attr(Attribute.FileName(0, "\$Boot", 0, false))
            attr(Attribute.DataNonResident(0, 1, clusterSize.toLong(), clusterSize))
        }
        
        val stubs = listOf("\$BadClus", "\$Secure", "\$UpCase", "\$Extend", "R12", "R13", "R14", "R15")
        for (i in 8..15) {
            builder.build(i) {
                attr(Attribute.StandardInfo())
                attr(Attribute.FileName(0, stubs[i-8], 0, false))
                if (i == 10) {
                    attr(Attribute.DataNonResident(upcaseLcn, (upcaseData.size + clusterSize - 1L) / clusterSize, upcaseData.size.toLong(), clusterSize))
                } else {
                    attr(Attribute.Data(ByteArray(0)))
                }
                if (i == 11) directory()
            }
        }

        // ── 4. Flush to Disk with proper alignment ────────
        
        // Write MFT (padded to sector boundary)
        fun write(relativeLba: Long, data: ByteArray) {
            val sectors = (data.size + bytesPerSector - 1) / bytesPerSector
            require(relativeLba >= 0 && relativeLba + sectors <= partitionSectorCount) {
                "NTFS metadata write at relative LBA $relativeLba ($sectors sectors) exceeds the partition"
            }
            writeSectorAligned(device, partitionStartLba + relativeLba, data)
        }
        write(mftLcn * sectorsPerCluster, mftData)

        val attrDefPadded = attrDefData.copyOf((attrDefClusters * clusterSize).toInt())
        write(attrDefLcn * sectorsPerCluster, attrDefPadded)
        
        // Write MFT Mirror (first 4 records)
        val mirrorData = mftData.sliceArray(0 until 4 * MFT_RECORD_SIZE)
        write(mftMirrLcn * sectorsPerCluster, mirrorData)
        
        // Write LogFile (filled with 0xFF, aligned to cluster)
        val logClusters = (logSize + clusterSize - 1) / clusterSize
        val logData = ByteArray((logClusters * clusterSize).toInt()) { 0xFF.toByte() }
        write(logLcn * sectorsPerCluster, logData)
        
        // Write UpCase (padded to cluster boundary)
        val upcasePadded = upcaseData.copyOf(((upcaseData.size + clusterSize - 1) / clusterSize * clusterSize).toInt())
        write(upcaseLcn * sectorsPerCluster, upcasePadded)
        
        // Write Bitmap
        val bitmapClusters = (bitmapSize + clusterSize - 1) / clusterSize
        val bitmapData = ByteArray((bitmapClusters * clusterSize).toInt())
        val lastReserved = upcaseLcn + upcaseClusters
        for (i in 0 until lastReserved.toInt()) {
            val bi = i / 8
            if (bi < bitmapData.size) {
                bitmapData[bi] = (bitmapData[bi].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        for (cluster in mftMirrLcn until mftMirrLcn + mirrorClusters) {
            val bi = (cluster / 8).toInt()
            bitmapData[bi] = (bitmapData[bi].toInt() or (1 shl (cluster % 8).toInt())).toByte()
        }
        write(bitmapLcn * sectorsPerCluster, bitmapData)
        
        // Write $Boot data at LCN 0
        val bootData = ByteArray(bytesPerSector)
        System.arraycopy(bootSector, 0, bootData, 0, bytesPerSector)
        write(0, bootData)
    }
    
    private fun writeSectorAligned(device: BlockWriter, startLba: Long, data: ByteArray) {
        val blockSize = device.blockSize
        // Ensure data is a multiple of blockSize
        val alignedData = if (data.size % blockSize == 0) {
            data
        } else {
            val padded = ByteArray(((data.size / blockSize) + 1) * blockSize)
            System.arraycopy(data, 0, padded, 0, data.size)
            padded
        }
        device.writeBlocks(startLba, alignedData)
    }

    private fun buildBootSector(bps: Int, spc: Int, tot: Long, mft: Long, mir: Long, sn: Long, hidden: Long): ByteArray {
        val s = ByteArray(bps)
        val b = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        
        // Jump instruction
        b.put(0xEB.toByte())
        b.put(0x52.toByte())
        b.put(0x90.toByte())
        
        // OEM ID
        b.put("NTFS    ".toByteArray(StandardCharsets.US_ASCII))
        
        // Bytes per sector (offset 0x0B)
        b.position(0x0B)
        b.putShort(bps.toShort())
        
        // Sectors per cluster (offset 0x0D)
        b.put(spc.toByte())
        
        // Reserved sectors (offset 0x0E) - always 0 for NTFS
        b.position(0x0E)
        b.putShort(0)
        
        // Media descriptor (offset 0x15)
        b.position(0x15)
        b.put(0xF8.toByte())
        
        // Sectors per track (offset 0x18)
        b.position(0x18)
        b.putShort(63.toShort())
        
        // Number of heads (offset 0x1A)
        b.putShort(255.toShort())
        
        // Hidden sectors (offset 0x1C)
        b.position(0x1C)
        b.putInt(hidden.toInt())
        
        // Total sectors (offset 0x28) is the volume sector count.
        b.position(0x28)
        b.putLong(tot)
        
        // MFT start LCN (offset 0x30)
        b.putLong(mft)
        
        // MFT Mirror start LCN (offset 0x38)
        b.putLong(mir)
        
        // Clusters per MFT record (offset 0x40)
        b.position(0x40)
        b.put(0xF6.toByte())  // -10 = 1024 bytes
        
        // Clusters per index block (offset 0x44)
        // FIX: was missing b.position(0x44); after writing 1 byte at 0x40 the
        //      cursor was at 0x41, so the value ended up at 0x41 instead of 0x44.
        b.position(0x44)
        b.put((maxOf(INDEX_BLOCK_SIZE, spc * bps) / (spc * bps)).toByte())
        
        // Serial number (offset 0x48)
        b.position(0x48)
        b.putLong(sn)
        
        // Boot signature
        s[510] = 0x55
        s[511] = 0xAA.toByte()
        
        return s
    }

    private fun buildUpCase(): ByteArray {
        val d = ByteArray(128 * 1024)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 65536) {
            b.putShort(i.toChar().uppercaseChar().code.toShort())
        }
        return d
    }

    private fun buildAttrDefFull(): ByteArray {
        // Standard NTFS attribute definitions
        val attrs = listOf(
            Pair(0x10, "STANDARD_INFORMATION"),
            Pair(0x20, "ATTRIBUTE_LIST"),
            Pair(0x30, "FILE_NAME"),
            Pair(0x40, "OBJECT_ID"),
            Pair(0x50, "SECURITY_DESCRIPTOR"),
            Pair(0x60, "VOLUME_NAME"),
            Pair(0x70, "VOLUME_INFORMATION"),
            Pair(0x80, "DATA"),
            Pair(0x90, "INDEX_ROOT"),
            Pair(0xA0, "INDEX_ALLOCATION"),
            Pair(0xB0, "BITMAP"),
            Pair(0xC0, "REPARSE_POINT"),
            Pair(0xD0, "EA_INFORMATION"),
            Pair(0xE0, "EA"),
            Pair(0x100, "LOGGED_UTILITY_STREAM")
        )
        
        // ATTR_DEF records are 0xA0 bytes with a fixed 128-byte UTF-16 name.
        val recordSize = 0xA0
        val buffer = ByteBuffer.allocate(attrs.size * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        for ((type, name) in attrs) {
            val start = buffer.position()
            val nameBytes = name.toByteArray(StandardCharsets.UTF_16LE)
            buffer.put(nameBytes)
            buffer.position(start + 0x80)
            buffer.putInt(type)
            buffer.putInt(0) // display rule
            buffer.putInt(0) // collation rule
            buffer.putInt(0) // flags
            buffer.putLong(0) // minimum size
            buffer.putLong(Long.MAX_VALUE) // maximum size
            buffer.position(start + recordSize)
        }
        
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    private class MftBuilder(val data: ByteArray, val rec: Int, val bps: Int) {
        fun build(idx: Int, init: MftRecord.() -> Unit) {
            val r = MftRecord(idx)
            r.init()
            val b = r.toByteArray(rec, bps)
            System.arraycopy(b, 0, data, idx * rec, rec)
        }
    }

    private class MftRecord(val idx: Int) {
        private val attrs = mutableListOf<Attribute>()
        private var nextId = 0
        private var isDirectory = false
        
        fun attr(a: Attribute) { 
            a.id = nextId++
            attrs.add(a) 
        }
        fun directory() { isDirectory = true }

        fun toByteArray(size: Int, bps: Int): ByteArray {
            val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            
            val sectorCount = size / bps

            // NTFS 3.1 layout: fixup array (Update Sequence Array) lives at 0x30.
            // The extended header adds 2-byte padding and 4-byte record number at
            // 0x28–0x2F, so the first attribute starts at 0x38.
            // FIX: was `val fixupOffset = 0x2A` — wrong for NTFS 3.1; must be 0x30.
            val fixupOffset = 0x30

            // FIX: fixupCount must include the USN slot itself (+1), not just sector count.
            //      Was `size / bps`; must be `sectorCount + 1`.
            val fixupCount = sectorCount + 1

            // Signature (0x00)
            b.put("FILE".toByteArray(StandardCharsets.US_ASCII))

            // FIX: the original code jumped straight to b.position(0x2A) and wrote
            //      all header fields from there, leaving 0x04–0x29 as all-zeros.
            //      Every field below is now written at its correct NTFS 3.1 offset.

            // Update Sequence Array Offset (0x04)
            b.position(0x04)
            b.putShort(fixupOffset.toShort())

            // Update Sequence Array Count (0x06) — USN + one entry per sector
            b.putShort(fixupCount.toShort())

            // $LogFile Sequence Number (0x08)
            b.putLong(0L)

            // Sequence Number (0x10)
            b.position(0x10)
            b.putShort(1)

            // Hard Link Count (0x12)
            b.putShort(1)

            // First Attribute Offset (0x14) — attributes start at 0x38
            b.putShort(0x38.toShort())

            // Flags (0x16) — 0x0001 = in-use
            b.putShort((if (isDirectory) 0x0003 else 0x0001).toShort())

            // Real/Used Size (0x18) — filled in after attributes are written
            b.putInt(0)

            // Allocated Size (0x1C)
            b.putInt(size)

            // Base File Record reference (0x20)
            b.putLong(0L)

            // Next Attribute ID (0x28)
            b.position(0x28)
            b.putShort(nextId.toShort())

            // Padding (0x2A) — NTFS 3.1 alignment word
            b.putShort(0)

            // MFT Record Number (0x2C) — NTFS 3.1 extended header field
            b.putInt(idx)

            // Update Sequence Number (0x30) — the value written to sector endings.
            // FIX: was never written; the USN must be stored here so the NTFS driver
            //      can verify the sector-end stamps when reading the record back.
            b.putShort(0x0001)   // USN = 1

            // Fixup array entries (0x32 … 0x32 + sectorCount*2 - 1) — filled during
            // fixup application below; initialise to zero for now.
            repeat(sectorCount) { b.putShort(0) }

            // Write attributes starting at 0x38
            b.position(0x38)
            for (attr in attrs) {
                val attrBytes = attr.toByteArray()
                b.put(attrBytes)
            }
            
            // End marker (0xFFFFFFFF)
            b.putInt(0xFFFFFFFF.toInt())
            
            // Back-fill real/used size
            val usedSize = b.position()
            b.position(0x18)
            b.putInt(usedSize)
            
            val arr = b.array()

            // ── Apply fixup ──────────────────────────────────────────────────────────
            // Read the USN that was written at fixupOffset (little-endian short).
            val usnLo = arr[fixupOffset]
            val usnHi = arr[fixupOffset + 1]

            for (i in 0 until sectorCount) {
                // Last two bytes of each sector within this MFT record.
                val sectorEnd = (i + 1) * bps - 2

                // Save the original bytes into the fixup array entry for sector i.
                // FIX: fixupPos was computed with the old (wrong) fixupOffset = 0x2A,
                //      and was also byte-order-swapped (big-endian store in a LE buffer).
                //      Now we store the bytes verbatim in little-endian order.
                val entryPos = fixupOffset + 2 + i * 2
                arr[entryPos]     = arr[sectorEnd]
                arr[entryPos + 1] = arr[sectorEnd + 1]

                // Stamp the sector ending with the USN.
                arr[sectorEnd]     = usnLo
                arr[sectorEnd + 1] = usnHi
            }
            
            return arr
        }
    }

    private abstract class Attribute(val t: Int) {
        var id: Int = 0
        abstract fun toByteArray(): ByteArray
        
        protected fun hdr(t: Int, d: ByteArray): ByteArray {
            val h = 24
            val s = (h + d.size + 7) and 0xFFFFFFF8.toInt()
            val r = ByteArray(s)
            val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(t)
            b.putInt(s)
            b.put(0.toByte())
            b.put(0.toByte())
            b.putShort(h.toShort())
            b.putShort(0.toShort())
            b.putShort(id.toShort())
            b.putInt(d.size)
            b.putShort(h.toShort())
            b.putShort(0.toShort())
            (b as Buffer).position(h)
            b.put(d)
            return r
        }

        class StandardInfo : Attribute(0x10) {
            override fun toByteArray(): ByteArray {
                val d = ByteArray(48)
                val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
                val n = System.currentTimeMillis() * 10000 + 116444736000000000L
                b.putLong(n)
                b.putLong(n)
                b.putLong(n)
                b.putLong(n)
                b.putInt(0x20)
                return hdr(t, d)
            }
        }
        
        class FileName(val p: Long, val n: String, val s: Long, val d: Boolean) : Attribute(0x30) {
            override fun toByteArray(): ByteArray {
                val nb = n.toByteArray(StandardCharsets.UTF_16LE)
                val da = ByteArray(66 + nb.size)
                val b = ByteBuffer.wrap(da).order(ByteOrder.LITTLE_ENDIAN)
                val now = System.currentTimeMillis() * 10000 + 116444736000000000L
                b.putLong(p)
                (b as Buffer).position(8)
                b.putLong(now)
                b.putLong(now)
                b.putLong(now)
                b.putLong(now)
                b.putLong(s)
                b.putLong(s)
                b.putInt(if (d) 0x10000000 else 0x20)
                // FIX: the 4-byte reparse/EA field at 0x3C was missing, which pushed the
                // name length, namespace and name 4 bytes too low (0x3C instead of 0x40).
                // NTFS then read garbage as the file name and refused to mount the volume.
                b.putInt(0)
                b.put(n.length.toByte())
                b.put(0x03.toByte())
                b.put(nb)

                return hdr(t, da)
            }
        }
        
        class Data(val b: ByteArray) : Attribute(0x80) { 
            override fun toByteArray() = hdr(t, b) 
        }
        
        class DataNonResident(val l: Long, val c: Long, val r: Long, val cl: Int) : Attribute(0x80) {
            override fun toByteArray(): ByteArray {
                val rl = buildRunList(l, c)
                val h = 64
                val s = (h + rl.size + 7) and 0xFFFFFFF8.toInt()
                val res = ByteArray(s)
                val b = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN)
                
                b.putInt(t)
                b.putInt(s)
                b.put(1.toByte())
                b.put(0.toByte())
                b.putShort(0.toShort())
                b.putShort(0.toShort())
                b.putShort(id.toShort())
                b.putLong(0L)
                b.putLong(c - 1)
                b.putShort(h.toShort())
                b.putShort(0.toShort())
                b.putInt(0)
                b.putLong(c * cl)
                b.putLong(r)
                b.putLong(r)
                (b as Buffer).position(h)
                b.put(rl)
                
                return res
            }
            
            private fun buildRunList(l: Long, c: Long): ByteArray {
                // Encode length
                val lenBytes = mutableListOf<Byte>()
                var len = c
                while (true) {
                    lenBytes.add((len and 0xFF).toByte())
                    len = len shr 8
                    if (len == 0L) break
                }
                
                // Encode offset
                val offBytes = mutableListOf<Byte>()
                var off = l
                val isNegative = off < 0
                if (isNegative) off = -off
                
                while (true) {
                    offBytes.add((off and 0xFF).toByte())
                    off = off shr 8
                    if (off == 0L) break
                }
                
                if (isNegative && offBytes.isNotEmpty()) {
                    val lastIndex = offBytes.size - 1
                    offBytes[lastIndex] = (offBytes[lastIndex].toInt() or 0x80).toByte()
                }
                
                val result = ByteArray(1 + lenBytes.size + offBytes.size + 1)
                // FIX: the nibbles were swapped. In an NTFS run list the LOW nibble is
                // the length field size and the HIGH nibble the offset field size; the
                // old code wrote (length shl 4) or offset, so every run whose two fields
                // had different byte widths decoded to the wrong cluster.
                result[0] = ((offBytes.size shl 4) or lenBytes.size).toByte()

                System.arraycopy(lenBytes.toByteArray(), 0, result, 1, lenBytes.size)
                System.arraycopy(offBytes.toByteArray(), 0, result, 1 + lenBytes.size, offBytes.size)
                result[result.size - 1] = 0
                
                return result
            }
        }
        
        class VolumeName(val n: String) : Attribute(0x60) { 
            override fun toByteArray() = hdr(t, n.toByteArray(StandardCharsets.UTF_16LE)) 
        }
        
        class VolumeInfo : Attribute(0x70) {
            override fun toByteArray(): ByteArray {
                val d = ByteArray(12)
                val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
                b.putLong(0L)
                b.put(3.toByte())
                b.put(1.toByte())
                b.putShort(0.toShort())
                return hdr(t, d)
            }
        }
        
        class IndexRoot(private val indexBlockSize: Int) : Attribute(0x90) {
            override fun toByteArray(): ByteArray {
                val d = ByteArray(48)
                val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt(0x30)
                b.putInt(0x01)
                b.putInt(indexBlockSize)
                b.put(1.toByte())
                (b as Buffer).position(16)
                b.putInt(16)
                b.putInt(32)
                b.putInt(32)
                b.put(0.toByte())
                (b as Buffer).position(32)
                b.putLong(0L)
                b.putShort(16.toShort())
                b.putShort(0.toShort())
                b.putInt(0x02)
                return hdr(t, d)
            }
        }
    }
}

