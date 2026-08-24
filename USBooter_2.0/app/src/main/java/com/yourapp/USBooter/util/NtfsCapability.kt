package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Verifies that this build can really produce a mountable NTFS volume.
 *
 * Rather than trusting a hardcoded "NTFS supported" flag, this runs
 * [NtfsFormatter] against an in-memory drive and then parses the bytes it wrote
 * back the same way an NTFS driver would: boot sector fields, the 16 system MFT
 * records (signature, update-sequence fixups, attribute chain), the \$MFT run
 * list, \$Volume/$Root attributes, \$Bitmap reservations and \$UpCase.
 *
 * If anything is off, formatting would produce a volume Windows refuses to
 * mount, so the UI must say NTFS is unavailable instead of silently writing a
 * broken filesystem.
 */
object NtfsCapability {

    /** Error code surfaced to the UI when the self-test fails. */
    const val CODE_UNAVAILABLE = "E-FS-02"

    data class Result(
        val available: Boolean,
        /** Machine-readable reason id, e.g. "mft_fixup". Null when available. */
        val reason: String? = null,
        /** Technical detail for the error report panel. */
        val detail: String = ""
    )

    @Volatile
    private var cached: Result? = null

    /** Cached probe at the standard 512-byte sector size. */
    fun probe(): Result = cached ?: probe(512).also { cached = it }

    /** Runs the full self-test for a given sector size. Not cached. */
    fun probe(blockSize: Int): Result {
        return try {
            val totalSectors = 1_000_000L          // ~512 MB test volume
            val start = 2048L
            val mem = MemoryBlockWriter(blockSize, start + totalSectors)
            NtfsFormatter.format(mem, start, totalSectors, "USBOOTER")
            validate(mem, start, totalSectors, blockSize)
        } catch (e: Throwable) {
            Result(false, "exception", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private fun validate(mem: MemoryBlockWriter, start: Long, totalSectors: Long, bps: Int): Result {
        // 1. Boot sector
        val boot = mem.read(start, 1) ?: return Result(false, "boot_missing", "no boot sector written at LBA $start")
        val b = le(boot)
        val oem = String(boot, 3, 8, StandardCharsets.US_ASCII)
        if (oem != "NTFS    ") return Result(false, "boot_oem", "OEM id is '$oem', expected 'NTFS    '")
        val bytesPerSector = b.getShort(0x0B).toInt() and 0xFFFF
        if (bytesPerSector != bps) return Result(false, "boot_bps", "bytes/sector $bytesPerSector != $bps")
        val spc = boot[0x0D].toInt() and 0xFF
        if (spc == 0 || (spc and (spc - 1)) != 0) return Result(false, "boot_spc", "sectors/cluster $spc is not a power of two")
        val clusterSize = spc * bytesPerSector
        val totalField = b.getLong(0x28)
        if (totalField != totalSectors) return Result(false, "boot_total", "total sectors field $totalField != $totalSectors")
        val mftLcn = b.getLong(0x30)
        val mftMirrLcn = b.getLong(0x38)
        val totalClusters = totalSectors / spc
        if (mftLcn <= 0 || mftLcn >= totalClusters) return Result(false, "boot_mft_lcn", "\$MFT LCN $mftLcn out of range")
        if (mftMirrLcn <= 0 || mftMirrLcn >= totalClusters) return Result(false, "boot_mirr_lcn", "\$MFTMirr LCN $mftMirrLcn out of range")
        val clustersPerMftRecord = boot[0x40].toInt() and 0xFF
        if (clustersPerMftRecord != 0xF6) return Result(false, "boot_rec_size", "clusters-per-MFT-record byte 0x%02X != 0xF6".format(clustersPerMftRecord))
        if ((boot[0x44].toInt() and 0xFF) == 0) return Result(false, "boot_index_size", "clusters-per-index-block byte is 0 (field written at the wrong offset)")
        if (b.getLong(0x48) == 0L) return Result(false, "boot_serial", "volume serial number is zero")
        if ((boot[510].toInt() and 0xFF) != 0x55 || (boot[511].toInt() and 0xFF) != 0xAA) {
            return Result(false, "boot_signature", "missing 0x55AA boot signature")
        }
        val backup = mem.read(start + totalSectors - 1, 1)
            ?: return Result(false, "boot_backup", "no backup boot sector at the last partition sector")
        if (!backup.contentEquals(boot)) return Result(false, "boot_backup", "backup boot sector differs from the primary one")

        // 2. MFT
        val recSize = 1024
        val mftBytes = mem.read(start + mftLcn * spc, 16 * recSize / bps)
            ?: return Result(false, "mft_missing", "no \$MFT written at LCN $mftLcn")
        val names = arrayOf(
            "\$MFT", "\$MFTMirr", "\$LogFile", "\$Volume", "\$AttrDef", ".",
            "\$Bitmap", "\$Boot", "\$BadClus", "\$Secure", "\$UpCase", "\$Extend"
        )
        for (i in 0 until 16) {
            val rec = mftBytes.copyOfRange(i * recSize, (i + 1) * recSize)
            val r = validateRecord(rec, i, bps, if (i < names.size) names[i] else null)
            if (r != null) return r
        }

        // 3. \$MFT ($DATA) run list must point at mftLcn
        val mftAttrs = attributes(mftBytes.copyOfRange(0, recSize)).orEmpty()
        val data = mftAttrs.firstOrNull { it.type == 0x80 }
            ?: return Result(false, "mft_no_data", "\$MFT record has no \$DATA attribute")
        if (data.resident) return Result(false, "mft_resident", "\$MFT \$DATA is resident; it must be non-resident")
        val runs = decodeRunList(data.body, data.runListOffset)
            ?: return Result(false, "mft_runlist", "\$MFT run list could not be decoded")
        if (runs.isEmpty() || runs[0].second != mftLcn) {
            return Result(false, "mft_runlist", "\$MFT run list starts at LCN ${runs.firstOrNull()?.second} but the boot sector says $mftLcn")
        }

        // 4. \$Volume must carry the name + volume information
        val volAttrs = attributes(mftBytes.copyOfRange(3 * recSize, 4 * recSize)).orEmpty().map { it.type }
        if (0x60 !in volAttrs) return Result(false, "vol_name", "\$Volume record has no \$VOLUME_NAME")
        if (0x70 !in volAttrs) return Result(false, "vol_info", "\$Volume record has no \$VOLUME_INFORMATION")

        // 5. Root directory must carry an index root
        val rootAttrs = attributes(mftBytes.copyOfRange(5 * recSize, 6 * recSize)).orEmpty().map { it.type }
        if (0x90 !in rootAttrs) return Result(false, "root_index", "root directory record has no \$INDEX_ROOT")

        // 6. \$MFTMirr must mirror the first four records
        val mirror = mem.read(start + mftMirrLcn * spc, 4 * recSize / bps)
            ?: return Result(false, "mirror_missing", "no \$MFTMirr data at LCN $mftMirrLcn")
        if (!mirror.copyOfRange(0, 4 * recSize).contentEquals(mftBytes.copyOfRange(0, 4 * recSize))) {
            return Result(false, "mirror_mismatch", "\$MFTMirr does not match the first four MFT records")
        }

        // 7. \$Bitmap must mark the metadata clusters as in use
        val bitmapAttr = attributes(mftBytes.copyOfRange(6 * recSize, 7 * recSize)).orEmpty().firstOrNull { it.type == 0x80 }
            ?: return Result(false, "bitmap_attr", "\$Bitmap record has no \$DATA attribute")
        val bitmapRuns = decodeRunList(bitmapAttr.body, bitmapAttr.runListOffset)
            ?: return Result(false, "bitmap_runlist", "\$Bitmap run list could not be decoded")
        val bitmapLcn = bitmapRuns.firstOrNull()?.second
            ?: return Result(false, "bitmap_runlist", "\$Bitmap run list is empty")
        // The bitmap must be read far enough to cover every cluster we check:
        // \$MFT sits tens of thousands of clusters in, so one cluster of bitmap
        // (4096 B = 32768 bits) is not always enough and indexing past it used
        // to throw ArrayIndexOutOfBoundsException, reported as "NTFS unavailable".
        val mirrorClusters = (4 * recSize + clusterSize - 1) / clusterSize
        val highestCluster = maxOf(mftLcn + 16, mftMirrLcn + mirrorClusters)
        val neededBytes = highestCluster / 8 + 1
        val bitmapSectors = ((neededBytes + bps - 1) / bps).toInt().coerceAtLeast(spc)
        val bitmap = mem.read(start + bitmapLcn * spc, bitmapSectors)
            ?: return Result(false, "bitmap_missing", "no \$Bitmap data at LCN $bitmapLcn")
        if (bitmap.size < neededBytes) {
            return Result(
                false, "bitmap_short",
                "\$Bitmap holds ${bitmap.size} bytes but $neededBytes are needed to cover cluster $highestCluster"
            )
        }
        for (cluster in 0 until mftLcn.toInt() + 16) {
            val bit = (bitmap[cluster / 8].toInt() shr (cluster % 8)) and 1
            if (bit != 1) return Result(false, "bitmap_unset", "cluster $cluster holds metadata but is marked free in \$Bitmap")
        }
        for (cluster in mftMirrLcn until mftMirrLcn + mirrorClusters) {
            val bit = (bitmap[(cluster / 8).toInt()].toInt() shr (cluster % 8).toInt()) and 1
            if (bit != 1) return Result(false, "bitmap_mirror_unset", "\$MFTMirr cluster $cluster is marked free in \$Bitmap")
        }


        // 8. \$UpCase must be a full 64K-entry table with real mappings
        val upcaseAttr = attributes(mftBytes.copyOfRange(10 * recSize, 11 * recSize)).orEmpty().firstOrNull { it.type == 0x80 }
            ?: return Result(false, "upcase_attr", "\$UpCase record has no \$DATA attribute")
        if (upcaseAttr.resident) return Result(false, "upcase_resident", "\$UpCase \$DATA is resident")
        if (upcaseAttr.realSize != 128L * 1024) {
            return Result(false, "upcase_size", "\$UpCase is ${upcaseAttr.realSize} bytes, expected 131072")
        }
        val upcaseLcn = decodeRunList(upcaseAttr.body, upcaseAttr.runListOffset)?.firstOrNull()?.second
            ?: return Result(false, "upcase_runlist", "\$UpCase run list could not be decoded")
        val upcase = mem.read(start + upcaseLcn * spc, (128 * 1024 / bps))
            ?: return Result(false, "upcase_missing", "no \$UpCase data at LCN $upcaseLcn")
        val up = le(upcase)
        if (up.getShort(0x61 * 2).toInt() != 'A'.code) {
            return Result(false, "upcase_table", "\$UpCase maps 'a' to U+%04X instead of 'A'".format(up.getShort(0x61 * 2).toInt()))
        }
        if (up.getShort('A'.code * 2).toInt() != 'A'.code) {
            return Result(false, "upcase_table", "\$UpCase does not map 'A' to itself")
        }

        // 9. Nothing may be written outside the partition
        val outside = mem.writtenOutside(start, totalSectors)
        if (outside != null) {
            return Result(false, "out_of_bounds", "the formatter wrote to LBA $outside, outside the partition ($start..${start + totalSectors - 1})")
        }

        return Result(true, null, "NTFS 3.1 self-test passed: boot sector, 16 MFT records, mirror, bitmap and \$UpCase verified (cluster size $clusterSize B)")
    }

    private fun validateRecord(rec: ByteArray, index: Int, bps: Int, expectedName: String?): Result? {
        val b = le(rec)
        val sig = String(rec, 0, 4, StandardCharsets.US_ASCII)
        if (sig != "FILE") return Result(false, "mft_signature", "MFT record $index signature is '$sig', expected 'FILE'")
        val usaOffset = b.getShort(0x04).toInt() and 0xFFFF
        val usaCount = b.getShort(0x06).toInt() and 0xFFFF
        val sectors = rec.size / bps
        if (usaOffset != 0x30) return Result(false, "mft_usa_offset", "record $index update-sequence offset is 0x%02X, expected 0x30 (NTFS 3.1)".format(usaOffset))
        if (usaCount != sectors + 1) return Result(false, "mft_usa_count", "record $index update-sequence count is $usaCount, expected ${sectors + 1}")
        val firstAttr = b.getShort(0x14).toInt() and 0xFFFF
        if (firstAttr < usaOffset + usaCount * 2) return Result(false, "mft_attr_offset", "record $index first attribute offset 0x%02X overlaps the fixup array".format(firstAttr))
        if ((b.getShort(0x16).toInt() and 0x0001) == 0) return Result(false, "mft_in_use", "record $index is not flagged in-use")
        val used = b.getInt(0x18)
        val alloc = b.getInt(0x1C)
        if (alloc != rec.size) return Result(false, "mft_alloc", "record $index allocated size $alloc != ${rec.size}")
        if (used <= firstAttr || used > rec.size) return Result(false, "mft_used", "record $index used size $used is out of range")
        if (b.getInt(0x2C) != index) return Result(false, "mft_number", "record $index stores record number ${b.getInt(0x2C)}")

        // Fixups: the last two bytes of every sector must carry the USN.
        val usnLo = rec[usaOffset]
        val usnHi = rec[usaOffset + 1]
        if (usnLo == 0.toByte() && usnHi == 0.toByte()) {
            return Result(false, "mft_usn", "record $index has a zero update sequence number")
        }
        for (s in 0 until sectors) {
            val end = (s + 1) * bps - 2
            if (rec[end] != usnLo || rec[end + 1] != usnHi) {
                return Result(false, "mft_fixup", "record $index sector $s is not stamped with the update sequence number")
            }
        }

        // Attribute chain must terminate cleanly.
        val attrs = attributes(rec) ?: return Result(false, "mft_attrs", "record $index has a malformed attribute chain")
        if (attrs.isEmpty()) return Result(false, "mft_attrs", "record $index has no attributes")
        if (attrs.none { it.type == 0x10 }) return Result(false, "mft_std_info", "record $index has no \$STANDARD_INFORMATION")
        val nameAttr = attrs.firstOrNull { it.type == 0x30 }
            ?: return Result(false, "mft_file_name", "record $index has no \$FILE_NAME")
        if (expectedName != null) {
            val name = fileNameOf(nameAttr) 
            if (name != expectedName) return Result(false, "mft_file_name", "record $index is named '$name', expected '$expectedName'")
        }
        return null
    }

    // ── Attribute parsing ───────────────────────────────────────────────────

    private class Attr(
        val type: Int,
        val resident: Boolean,
        /** Whole attribute bytes. */
        val body: ByteArray,
        val runListOffset: Int,
        val realSize: Long,
        val contentOffset: Int,
        val contentLength: Int
    )

    private fun attributes(rec: ByteArray): List<Attr>? {
        val b = le(rec)
        val used = b.getInt(0x18)
        var pos = b.getShort(0x14).toInt() and 0xFFFF
        val out = mutableListOf<Attr>()
        while (true) {
            if (pos + 4 > rec.size) return null
            val type = b.getInt(pos)
            if (type == -1) return out                     // 0xFFFFFFFF end marker
            if (pos + 16 > rec.size) return null
            val len = b.getInt(pos + 4)
            if (len <= 0 || len % 8 != 0 || pos + len > used || pos + len > rec.size) return null
            val nonResident = rec[pos + 8].toInt() != 0
            val attr = if (nonResident) {
                Attr(
                    type, false, rec.copyOfRange(pos, pos + len),
                    runListOffset = b.getShort(pos + 0x20).toInt() and 0xFFFF,
                    realSize = b.getLong(pos + 0x30),
                    contentOffset = 0, contentLength = 0
                )
            } else {
                val contentLen = b.getInt(pos + 0x10)
                val contentOff = b.getShort(pos + 0x14).toInt() and 0xFFFF
                if (contentOff + contentLen > len) return null
                Attr(type, true, rec.copyOfRange(pos, pos + len), 0, contentLen.toLong(), contentOff, contentLen)
            }
            out.add(attr)
            pos += len
            if (out.size > 64) return null
        }
    }

    private fun fileNameOf(attr: Attr): String? {
        if (!attr.resident) return null
        val base = attr.contentOffset
        if (base + 66 > attr.body.size) return null
        val nameLen = attr.body[base + 64].toInt() and 0xFF
        val bytes = base + 66 + nameLen * 2
        if (bytes > attr.body.size) return null
        return String(attr.body, base + 66, nameLen * 2, StandardCharsets.UTF_16LE)
    }

    /** Returns (clusterCount, startLcn) pairs, or null if the run list is invalid. */
    private fun decodeRunList(attr: ByteArray, offset: Int): List<Pair<Long, Long>>? {
        if (offset <= 0 || offset >= attr.size) return null
        var pos = offset
        var lcn = 0L
        val runs = mutableListOf<Pair<Long, Long>>()
        while (pos < attr.size) {
            val header = attr[pos].toInt() and 0xFF
            if (header == 0) return runs
            val lenSize = header and 0x0F
            val offSize = (header shr 4) and 0x0F
            if (lenSize == 0 || pos + 1 + lenSize + offSize > attr.size) return null
            var count = 0L
            for (i in 0 until lenSize) count = count or ((attr[pos + 1 + i].toLong() and 0xFF) shl (8 * i))
            if (count <= 0) return null
            if (offSize > 0) {
                var delta = 0L
                for (i in 0 until offSize) delta = delta or ((attr[pos + 1 + lenSize + i].toLong() and 0xFF) shl (8 * i))
                // Sign-extend the signed LCN delta.
                val signBit = 1L shl (offSize * 8 - 1)
                if (delta and signBit != 0L) delta -= (1L shl (offSize * 8))
                lcn += delta
                if (lcn < 0) return null
            }
            runs.add(count to lcn)
            pos += 1 + lenSize + offSize
            if (runs.size > 64) return null
        }
        return runs
    }

    private fun le(a: ByteArray): ByteBuffer = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)

    // ── In-memory drive ─────────────────────────────────────────────────────

    private class MemoryBlockWriter(
        override val blockSize: Int,
        private val totalBlocks: Long
    ) : BlockWriter {
        private val blocks = HashMap<Long, ByteArray>()
        private var firstOutOfRange: Long? = null

        override fun writeBlocks(lba: Long, data: ByteArray) {
            require(data.size % blockSize == 0) { "write of ${data.size} bytes is not a multiple of $blockSize" }
            val count = data.size / blockSize
            for (i in 0 until count) {
                val target = lba + i
                if (target < 0 || target >= totalBlocks) {
                    if (firstOutOfRange == null) firstOutOfRange = target
                    continue
                }
                blocks[target] = data.copyOfRange(i * blockSize, (i + 1) * blockSize)
            }
        }

        /** Returns the requested blocks, or null if any of them was never written. */
        fun read(lba: Long, count: Int): ByteArray? {
            val out = ByteArray(count * blockSize)
            for (i in 0 until count) {
                val block = blocks[lba + i] ?: return null
                System.arraycopy(block, 0, out, i * blockSize, blockSize)
            }
            return out
        }

        fun writtenOutside(start: Long, sectorCount: Long): Long? {
            firstOutOfRange?.let { return it }
            return blocks.keys.firstOrNull { it < start || it >= start + sectorCount }
        }
    }
}
