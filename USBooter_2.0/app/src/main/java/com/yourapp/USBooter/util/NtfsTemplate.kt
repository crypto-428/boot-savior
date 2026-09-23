package com.yourapp.USBooter.util

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * Writes a real, Windows-made NTFS volume into a partition of *any* size.
 *
 * Instead of generating every NTFS structure by hand (see [NtfsFormatter]),
 * this writer ships a packed copy of a genuine NTFS volume and only recomputes
 * the handful of structures that actually depend on the volume size:
 *
 *   * boot sector: total sectors, hidden sectors, fresh serial + backup copy
 *   * `$Bitmap`  (MFT record 6): one non-resident run sized for the real cluster count
 *   * `$BadClus` (MFT record 8): one sparse run covering the whole volume
 *   * `$MFTMirr`: the first four MFT records copied to cluster 2
 *   * the cluster allocation bitmap itself
 *
 * Everything else - `$UpCase`, `$AttrDef`, `$Secure`, `$LogFile`, the root
 * directory index, all attribute layouts - is copied byte for byte from the
 * reference volume, which is why volumes written here mount as real NTFS.
 *
 * The packed template lives at `resources/ntfs/ntfs-template.bin.gz` and is
 * produced by `tools/pack-ntfs-template.py`; `tools/adapt-ntfs-template.py` is
 * the reference implementation this file ports.
 */
object NtfsTemplate {

    private const val RESOURCE = "/ntfs/ntfs-template.bin.gz"
    private const val MAGIC = "NTFSTPL2"
    private const val RECORD = 1024
    private const val BITMAP_REC = 6
    private const val BADCLUS_REC = 8
    private const val MIRROR_LCN = 2L
    private const val MFT_ZONE_RECORDS = 64

    /** Largest chunk handed to the device in one write (1 MiB). */
    private const val CHUNK_BYTES = 1 shl 20

    private class Template(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val mftLcn: Long,
        val runs: List<Pair<Long, Int>>,
        val data: ByteArray,
        val dataOffset: Int
    ) {
        val clusterSize: Int get() = bytesPerSector * sectorsPerCluster
    }

    @Volatile private var cached: Template? = null

    /** True when the packed reference volume is bundled with this build. */
    fun isAvailable(): Boolean = runCatching { load() }.isSuccess

    /**
     * Smallest volume the template fits into, in sectors of [deviceBlockSize].
     * Grows with nothing - the template's metadata sits in a fixed area - so
     * this is a constant of roughly 4 MiB.
     */
    fun minimumSectors(deviceBlockSize: Int): Long {
        val t = load()
        val highest = t.runs.maxOf { it.first + it.second }
        val neededClusters = maxOf(highest, t.mftLcn + MFT_ZONE_RECORDS) + 16
        val sectorsPerCluster = t.clusterSize / deviceBlockSize
        return neededClusters * sectorsPerCluster + 1
    }

    /** True when this drive geometry can be served by the packed template. */
    fun supports(deviceBlockSize: Int, partitionSectorCount: Long): Boolean {
        val t = runCatching { load() }.getOrNull() ?: return false
        // The template's MFT fixup arrays were produced for 512-byte sectors, and
        // the cluster size must stay a whole number of device sectors.
        if (deviceBlockSize != t.bytesPerSector) return false
        if (t.clusterSize % deviceBlockSize != 0) return false
        return partitionSectorCount >= minimumSectors(deviceBlockSize)
    }

    /**
     * Writes an empty NTFS volume covering exactly
     * [partitionStartLba] .. [partitionStartLba] + [partitionSectorCount] - 1.
     *
     * Works for every size from about 4 MiB upwards - 2 GB, 4 GB, 10 GB, 32 GB,
     * 2 TB - because every size-dependent structure is recomputed here.
     */
    fun format(
        device: BlockWriter,
        partitionStartLba: Long,
        partitionSectorCount: Long,
        volumeLabel: String = ""
    ) {
        val t = load()
        val bps = device.blockSize
        require(bps == t.bytesPerSector) {
            "the packed NTFS template needs ${t.bytesPerSector}-byte sectors (drive reports $bps)"
        }
        require(partitionSectorCount >= minimumSectors(bps)) {
            "NTFS partition is too small for the reference volume"
        }
        val cluster = t.clusterSize
        val spr = cluster / bps                       // device sectors per cluster
        // The very last sector holds the backup boot sector, so it is not part of
        // the cluster area.
        val clusters = (partitionSectorCount - 1) / spr
        require(clusters > t.runs.maxOf { it.first + it.second }) {
            "NTFS partition is too small for the reference volume"
        }

        fun write(lba: Long, data: ByteArray) {
            require(lba >= 0 && lba + data.size / bps <= partitionSectorCount) {
                "NTFS template write at sector $lba leaves the partition"
            }
            device.writeBlocks(partitionStartLba + lba, data)
        }

        // 1. Metadata clusters, verbatim - except the MFT region, which is patched
        //    below and written afterwards.
        val mftBytes = ByteArray(16 * RECORD)
        var offset = t.dataOffset
        for ((lcn, length) in t.runs) {
            val bytes = length.toLong() * cluster
            copyMftRegion(t, lcn, length, offset, mftBytes)
            writeStreamed(::write, lcn * spr, t.data, offset, bytes, bps)
            offset += bytes.toInt()
        }

        // 2. Boot sector: real size, hidden sectors and a fresh serial; the volume
        //    keeps a byte-identical backup copy in its last sector.
        val boot = t.data.copyOfRange(t.dataOffset, t.dataOffset + bps)
        putLe64(boot, 40, partitionSectorCount - 1)
        putLe32(boot, 0x1C, partitionStartLba)
        putLe64(boot, 72, Random.nextLong())
        putLe64(boot, 48, t.mftLcn)
        putLe64(boot, 56, MIRROR_LCN)
        write(0, boot)
        write(partitionSectorCount - 1, boot)

        // 3. $Bitmap and $BadClus describe the volume's size, so both records are
        //    rewritten to hold exactly one run of the real length.
        val bitmapBytes = (clusters + 7) / 8
        val bitmapClusters = ((bitmapBytes + cluster - 1) / cluster).toInt()
        val bitmapLcn = clusters - bitmapClusters      // volume tail, always free

        patchRecord(mftBytes, BITMAP_REC) { rec ->
            val a = findNonResident(rec, 0x80)
            nonResidentRun(rec, a, bitmapLcn, bitmapClusters.toLong(), bitmapBytes, cluster)
            closeRecord(rec, a)
        }
        patchRecord(mftBytes, BADCLUS_REC) { rec ->
            val a = findNonResident(rec, 0x80)
            sparseRun(rec, a, clusters, clusters * cluster, cluster)
            closeRecord(rec, a)
        }
        // 3b. The name the user typed replaces the reference volume's label.
        patchRecord(mftBytes, VOLUME_REC) { rec -> setVolumeLabel(rec, volumeLabel) }
        write(t.mftLcn * spr, mftBytes)

        // 4. $MFTMirr: the first four records, at cluster 2.
        write(MIRROR_LCN * spr, mftBytes.copyOfRange(0, 4 * RECORD))

        // 5. Cluster allocation bitmap - written in chunks so a 2 TB volume needs
        //    no more memory than a 2 GB one.
        writeClusterBitmap(
            ::write, t, clusters, bitmapLcn, bitmapClusters, bitmapBytes, cluster, spr
        )
    }

    // ── template loading ────────────────────────────────────────────────────

    private fun load(): Template {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val stream = NtfsTemplate::class.java.getResourceAsStream(RESOURCE)
                ?: throw IllegalStateException("the packed NTFS template is missing from this build")
            val raw = stream.use { input ->
                GZIPInputStream(input).use { gz ->
                    val out = ByteArrayOutputStream(4 shl 20)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = gz.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            }
            require(raw.size > 32 && String(raw, 0, 8, Charsets.US_ASCII) == MAGIC) {
                "the bundled NTFS template is not an $MAGIC image"
            }
            val bps = le16(raw, 8)
            val spc = le16(raw, 10)
            val mftLcn = le64(raw, 20)
            val runCount = le32(raw, 28).toInt()
            val runs = ArrayList<Pair<Long, Int>>(runCount)
            var o = 32
            repeat(runCount) {
                runs += le64(raw, o) to le32(raw, o + 8).toInt()
                o += 12
            }
            val template = Template(bps, spc, mftLcn, runs, raw, o)
            cached = template
            return template
        }
    }

    // ── writing helpers ─────────────────────────────────────────────────────

    private inline fun writeStreamed(
        write: (Long, ByteArray) -> Unit,
        startLba: Long,
        source: ByteArray,
        sourceOffset: Int,
        length: Long,
        bps: Int
    ) {
        var done = 0L
        while (done < length) {
            val chunk = minOf(CHUNK_BYTES.toLong(), length - done).toInt()
            val from = sourceOffset + done.toInt()
            write(startLba + done / bps, source.copyOfRange(from, from + chunk))
            done += chunk
        }
    }

    /** Copies the 16 MFT records out of the template run that contains them. */
    private fun copyMftRegion(
        t: Template,
        lcn: Long,
        length: Int,
        blobOffset: Int,
        into: ByteArray
    ) {
        if (t.mftLcn < lcn || t.mftLcn >= lcn + length) return
        val inside = ((t.mftLcn - lcn) * t.clusterSize).toInt()
        val from = blobOffset + inside
        System.arraycopy(t.data, from, into, 0, into.size)
    }

    private inline fun patchRecord(mft: ByteArray, index: Int, patch: (ByteArray) -> Unit) {
        val rec = mft.copyOfRange(index * RECORD, (index + 1) * RECORD)
        patch(rec)
        System.arraycopy(rec, 0, mft, index * RECORD, RECORD)
    }

    private fun writeClusterBitmap(
        write: (Long, ByteArray) -> Unit,
        t: Template,
        clusters: Long,
        bitmapLcn: Long,
        bitmapClusters: Int,
        bitmapBytes: Long,
        cluster: Int,
        spr: Int
    ) {
        // Clusters that must read as "in use": the template's own metadata, the
        // reserved MFT zone, the bitmap itself, and the padding bits that sit past
        // the end of the volume.
        val used = ArrayList<LongRange>()
        for ((lcn, length) in t.runs) used += lcn until (lcn + length)
        used += t.mftLcn until (t.mftLcn + MFT_ZONE_RECORDS)
        used += bitmapLcn until (bitmapLcn + bitmapClusters)
        used += clusters until bitmapBytes * 8

        val chunkClusters = maxOf(1, CHUNK_BYTES / cluster)
        var written = 0
        while (written < bitmapClusters) {
            val count = minOf(chunkClusters, bitmapClusters - written)
            val buf = ByteArray(count * cluster)
            val firstBit = written.toLong() * cluster * 8
            val lastBit = firstBit + count.toLong() * cluster * 8
            for (range in used) {
                var c = maxOf(range.first, firstBit)
                val end = minOf(range.last, lastBit - 1)
                while (c <= end) {
                    val bit = c - firstBit
                    val index = (bit / 8).toInt()
                    buf[index] = (buf[index].toInt() or (1 shl (bit % 8).toInt())).toByte()
                    c++
                }
            }
            write((bitmapLcn + written) * spr, buf)
            written += count
        }
    }

    // ── NTFS attribute surgery (ported from adapt-ntfs-template.py) ─────────

    private fun findNonResident(rec: ByteArray, wantedType: Int): Int {
        // $BadClus carries two $DATA attributes: an empty resident one and the
        // named non-resident "$Bad". Only the last, non-resident one has a runlist.
        var off = le16(rec, 20)
        var found = -1
        while (off + 8 < rec.size) {
            val type = le32(rec, off).toInt()
            if (type == -1 || type == 0xFFFFFFFF.toInt()) break
            val len = le32(rec, off + 4).toInt()
            if (len <= 0) break
            if (type == wantedType && rec[off + 8].toInt() == 1) found = off
            off += len
        }
        require(found >= 0) { "the NTFS template record has no non-resident attribute $wantedType" }
        return found
    }

    private fun nonResidentRun(
        rec: ByteArray,
        attrOff: Int,
        lcn: Long,
        clusters: Long,
        realBytes: Long,
        cluster: Int
    ) {
        putLe64(rec, attrOff + 16, 0)                       // start VCN
        putLe64(rec, attrOff + 24, clusters - 1)            // last VCN
        putLe64(rec, attrOff + 40, clusters * cluster)      // allocated
        putLe64(rec, attrOff + 48, realBytes)               // real size
        putLe64(rec, attrOff + 56, realBytes)               // initialised
        writeRunlist(rec, attrOff, encodeRun(clusters, lcn))
    }

    private fun sparseRun(
        rec: ByteArray,
        attrOff: Int,
        clusters: Long,
        sizeBytes: Long,
        cluster: Int
    ) {
        putLe64(rec, attrOff + 16, 0)
        putLe64(rec, attrOff + 24, clusters - 1)
        putLe64(rec, attrOff + 40, clusters * cluster)
        putLe64(rec, attrOff + 48, sizeBytes)
        putLe64(rec, attrOff + 56, 0)
        val len = encodeUnsigned(clusters)
        writeRunlist(rec, attrOff, byteArrayOf(len.size.toByte()) + len)
    }

    /** Writes a runlist in place; the record must keep its exact size. */
    private fun writeRunlist(rec: ByteArray, attrOff: Int, run: ByteArray) {
        val runOff = le16(rec, attrOff + 32)
        var padded = run + byteArrayOf(0)
        val pad = (8 - padded.size % 8) % 8
        if (pad > 0) padded += ByteArray(pad)
        val at = attrOff + runOff
        require(at + padded.size <= rec.size) { "the NTFS runlist does not fit in the MFT record" }
        System.arraycopy(padded, 0, rec, at, padded.size)
        putLe32(rec, attrOff + 4, (runOff + padded.size).toLong())
    }

    /** Puts the end marker after the patched attribute and fixes the used size. */
    private fun closeRecord(rec: ByteArray, attrOff: Int) {
        val end = attrOff + le32(rec, attrOff + 4).toInt()
        require(end + 8 <= rec.size) { "the patched NTFS record overflows" }
        putLe32(rec, end, 0xFFFFFFFFL)
        putLe32(rec, end + 4, 0)
        putLe32(rec, 24, (end + 8).toLong())
    }

    /** One data run: header nibbles, then the length and the signed LCN delta. */
    private fun encodeRun(length: Long, lcn: Long): ByteArray {
        val lb = encodeUnsigned(length)
        val ob = encodeSigned(lcn)
        return byteArrayOf(((ob.size shl 4) or lb.size).toByte()) + lb + ob
    }

    private fun encodeUnsigned(value: Long): ByteArray {
        var n = 1
        while (n < 8 && (value ushr (8 * n)) != 0L) n++
        return ByteArray(n) { ((value ushr (8 * it)) and 0xFF).toByte() }
    }

    private fun encodeSigned(value: Long): ByteArray {
        var n = 1
        while (n < 8) {
            val limit = 1L shl (8 * n - 1)
            if (value >= -limit && value < limit) break
            n++
        }
        return ByteArray(n) { ((value shr (8 * it)) and 0xFF).toByte() }
    }

    // ── little-endian primitives ────────────────────────────────────────────

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun putLe32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putLe64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
