package com.yourapp.USBooter.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits a single `install.wim` into a spanned `install.swm` / `install2.swm`
 * … set whose parts all stay below FAT32's 4 GB per-file limit, so the whole
 * Windows installer fits on one FAT32 partition.
 *
 * Why this exists: UEFI firmware only reads FAT, so the boot partition has to be
 * FAT32, but a modern `install.wim` is 4-6 GB. The previous workaround put the
 * image on a second exFAT partition; WinPE frequently refuses to pick that up
 * ("Windows cannot find the installation files"), because a lot of firmware and
 * WinPE builds only surface the first FAT partition of a removable stick.
 * A spanned SWM set is what Microsoft's own tooling (`dism /split-image`) and
 * Rufus produce, and Windows Setup loads it natively from FAT32.
 *
 * The split is *lossless and decompression-free*: a WIM is a header, a set of
 * (already compressed) resources, an offset table and an XML manifest. Splitting
 * only redistributes resources across files and rewrites the header + offset
 * table of each part, so nothing has to be recompressed on the phone. Every
 * resource's bytes are copied verbatim out of the ISO.
 *
 * Rules the produced set follows:
 *  - every part carries the same GUID, image count and XML manifest;
 *  - `usPartNumber` / `usTotalParts` are set per part, `WIM_HDR_FLAG_SPANNED`
 *    is set on all of them;
 *  - all image metadata resources live in part 1, as Setup expects;
 *  - each part's offset table lists exactly the resources stored in that part,
 *    at their new offsets, with the reference counts and SHA-1 hashes preserved.
 */
object WimSplitter {

    /**
     * Default cap per part. FAT32 allows 4 GiB - 1 byte; staying at 3.8 GiB keeps
     * a comfortable margin and matches what other splitters produce.
     */
    const val DEFAULT_MAX_PART_BYTES = 3800L * 1024 * 1024

    /** A region of a generated part file: either literal bytes or a range of the source WIM. */
    sealed class Segment {
        abstract val destOffset: Long
        abstract val length: Long

        /** Header, offset table or XML - small, generated in memory. */
        class Literal(override val destOffset: Long, val bytes: ByteArray) : Segment() {
            override val length: Long get() = bytes.size.toLong()
        }

        /** A stored resource, copied byte-for-byte from the WIM inside the ISO. */
        class FromWim(override val destOffset: Long, val wimOffset: Long, override val length: Long) : Segment()
    }

    /** One generated `.swm` file. Its bytes are produced on demand by [Plan.read]. */
    class Part(
        val name: String,
        val sizeBytes: Long,
        val resourceCount: Int,
        internal val segments: List<Segment>
    ) {
        /** Segment start offsets, ascending - the search key for [segmentAt]. */
        private val starts: LongArray = LongArray(segments.size) { segments[it].destOffset }

        /** The segment covering [pos], or null when nothing is mapped there. */
        internal fun segmentAt(pos: Long): Segment? {
            var low = 0
            var high = starts.size - 1
            var found = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (starts[mid] <= pos) {
                    found = mid
                    low = mid + 1
                } else high = mid - 1
            }
            return if (found < 0) null else segments[found]
        }
    }

    /**
     * The complete split, computed before a single byte is written: every part's
     * name and exact final size is known up front, which is what lets the FAT32
     * writer stream them straight onto the drive.
     */
    class Plan(
        /** Path of the source file inside the image, e.g. `sources/install.wim`. */
        val sourcePath: String,
        /** Directory the parts are written into, e.g. `sources`. */
        val targetDirectory: String,
        val parts: List<Part>,
        val images: List<WimReader.Image>,
        private val source: ByteReader,
        private val entry: IsoEntry
    ) {
        val totalBytes: Long get() = parts.sumOf { it.sizeBytes }
        val largestPartBytes: Long get() = parts.maxOfOrNull { it.sizeBytes } ?: 0L

        /** Full paths of the generated files, e.g. `sources/install.swm`. */
        val partPaths: List<String>
            get() = parts.map { if (targetDirectory.isEmpty()) it.name else "$targetDirectory/${it.name}" }

        fun summary(): String =
            "${parts.size} part(s): " + parts.joinToString(", ") { "${it.name} ${it.sizeBytes / (1024 * 1024)} MB" }

        /** Reads [length] bytes at [offset] of part [partIndex], assembling segments as needed. */
        fun read(partIndex: Int, offset: Long, length: Int): ByteArray {
            val part = parts[partIndex]
            if (offset < 0 || offset + length > part.sizeBytes) {
                throw IOException("Read of $length bytes at $offset is outside ${part.name} (${part.sizeBytes} bytes)")
            }
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                val pos = offset + done
                // A large WIM has tens of thousands of resources, so the segment
                // that owns this offset is found by binary search, never by scan.
                val segment = part.segmentAt(pos)
                    ?: throw IOException("No data mapped at offset $pos of ${part.name}")
                val within = pos - segment.destOffset
                if (within >= segment.length) throw IOException("Gap at offset $pos of ${part.name}")
                val take = minOf((segment.length - within), (length - done).toLong()).toInt()
                when (segment) {
                    is Segment.Literal -> System.arraycopy(segment.bytes, within.toInt(), out, done, take)
                    is Segment.FromWim -> {
                        val chunk = entry.readAt(source, segment.wimOffset + within, take)
                        System.arraycopy(chunk, 0, out, done, take)
                    }
                }
                done += take
            }
            return out
        }
    }

    /** Why an image cannot be split on-device. */
    class NotSplittable(message: String) : IOException(message)

    /**
     * Computes the split for [entry] (which must be a WIM container).
     *
     * @param maxPartBytes hard cap for every produced part.
     * @throws NotSplittable when the container cannot be redistributed without
     *         decompressing it (solid/LZMS resources, already-split sets, or a
     *         single resource larger than the cap).
     */
    fun plan(
        source: ByteReader,
        entry: IsoEntry,
        maxPartBytes: Long = DEFAULT_MAX_PART_BYTES,
        baseName: String = "install"
    ): Plan {
        val info = WimReader.read(source, entry)
            ?: throw NotSplittable("${entry.path} is not a WIM container, so it cannot be split")

        if (info.isAlreadySplit) {
            throw NotSplittable("${entry.path} is already part ${info.partNumber} of ${info.totalParts}")
        }
        if (info.hasSolidResources) {
            throw NotSplittable(
                "${entry.path} uses solid (LZMS) compression - it must be exported with DISM before it can be split"
            )
        }
        if (info.xmlBytes.isEmpty()) {
            throw NotSplittable("${entry.path} has no readable XML manifest, so a split set cannot be described")
        }

        val metadata = info.resources.filter { it.isMetadata }.sortedBy { it.offsetInWim }
        val streams = info.resources.filter { !it.isMetadata }.sortedBy { it.offsetInWim }
        val xmlSize = info.xmlBytes.size.toLong()

        // Room every part loses to its own header, offset table and XML.
        fun overhead(resourceCount: Int): Long =
            WimReader.HEADER_SIZE + resourceCount.toLong() * WimReader.LOOKUP_ENTRY_SIZE + xmlSize

        val biggest = info.resources.maxByOrNull { it.sizeInWim }
        if (biggest != null && biggest.sizeInWim + overhead(1) > maxPartBytes) {
            throw NotSplittable(
                "one resource inside ${entry.path} is ${biggest.sizeInWim / (1024 * 1024)} MB, " +
                    "which cannot fit in a ${maxPartBytes / (1024 * 1024)} MB part"
            )
        }

        // ── Bin-pack: metadata resources always go into part 1, then the streams
        //    in their original order so sequential reads from the ISO stay linear.
        val groups = mutableListOf<MutableList<WimReader.Resource>>()
        var current = mutableListOf<WimReader.Resource>()
        var currentBytes = 0L
        metadata.forEach { current.add(it); currentBytes += it.sizeInWim }
        if (currentBytes + overhead(current.size) > maxPartBytes) {
            throw NotSplittable("the image metadata alone exceeds one FAT32-sized part")
        }

        streams.forEach { res ->
            val projected = currentBytes + res.sizeInWim + overhead(current.size + 1)
            if (current.isNotEmpty() && projected > maxPartBytes) {
                groups.add(current)
                current = mutableListOf()
                currentBytes = 0L
            }
            current.add(res)
            currentBytes += res.sizeInWim
        }
        if (current.isNotEmpty() || groups.isEmpty()) groups.add(current)

        val totalParts = groups.size
        if (totalParts > 65535) throw NotSplittable("this image would need more than 65535 parts")

        val parts = groups.mapIndexed { groupIndex, group ->
            val partNumber = groupIndex + 1
            val segments = mutableListOf<Segment>()
            val placed = mutableListOf<Pair<WimReader.Resource, Long>>()

            var offset = WimReader.HEADER_SIZE.toLong()
            group.forEach { res ->
                segments.add(Segment.FromWim(offset, res.offsetInWim, res.sizeInWim))
                placed.add(res to offset)
                offset += res.sizeInWim
            }

            val tableOffset = offset
            val tableBytes = buildOffsetTable(placed, partNumber)
            segments.add(Segment.Literal(tableOffset, tableBytes))
            offset += tableBytes.size

            val xmlOffset = offset
            segments.add(Segment.Literal(xmlOffset, info.xmlBytes))
            offset += xmlSize

            val header = buildHeader(
                info = info,
                partNumber = partNumber,
                totalParts = totalParts,
                tableOffset = tableOffset,
                tableSize = tableBytes.size.toLong(),
                xmlOffset = xmlOffset,
                xmlSize = xmlSize
            )
            segments.add(0, Segment.Literal(0, header))

            Part(
                name = if (partNumber == 1) "$baseName.swm" else "$baseName$partNumber.swm",
                sizeBytes = offset,
                resourceCount = group.size,
                segments = segments.sortedBy { it.destOffset }
            )
        }

        val oversized = parts.filter { it.sizeBytes >= 4L * 1024 * 1024 * 1024 }
        if (oversized.isNotEmpty()) {
            throw NotSplittable("part ${oversized.first().name} came out at ${oversized.first().sizeBytes} bytes")
        }

        return Plan(
            sourcePath = entry.path.trim('/'),
            targetDirectory = entry.path.trim('/').substringBeforeLast('/', ""),
            parts = parts,
            images = info.images,
            source = source,
            entry = entry
        )
    }

    // ── Structure writers ────────────────────────────────────────────────

    private fun putResourceHeader(
        buf: ByteBuffer,
        pos: Int,
        sizeInWim: Long,
        flags: Int,
        offset: Long,
        originalSize: Long
    ) {
        for (i in 0 until 7) buf.put(pos + i, ((sizeInWim shr (8 * i)) and 0xFF).toByte())
        buf.put(pos + 7, flags.toByte())
        buf.putLong(pos + 8, offset)
        buf.putLong(pos + 16, originalSize)
    }

    private fun buildOffsetTable(
        placed: List<Pair<WimReader.Resource, Long>>,
        partNumber: Int
    ): ByteArray {
        val bytes = ByteArray(placed.size * WimReader.LOOKUP_ENTRY_SIZE)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        placed.sortedBy { it.second }.forEachIndexed { index, (res, newOffset) ->
            val base = index * WimReader.LOOKUP_ENTRY_SIZE
            putResourceHeader(buf, base, res.sizeInWim, res.flags, newOffset, res.originalSize)
            buf.putShort(base + 24, partNumber.toShort())
            buf.putInt(base + 26, res.refCount)
            res.hash.copyInto(bytes, base + 30)
        }
        return bytes
    }

    private fun buildHeader(
        info: WimReader.Info,
        partNumber: Int,
        totalParts: Int,
        tableOffset: Long,
        tableSize: Long,
        xmlOffset: Long,
        xmlSize: Long
    ): ByteArray {
        val bytes = info.headerBytes.copyOf()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Same container, now flagged as one part of a spanned set.
        val flags = (info.flags or WimReader.HDR_FLAG_SPANNED) and WimReader.HDR_FLAG_WRITE_IN_PROGRESS.inv()
        buf.putInt(16, flags)
        buf.putShort(40, partNumber.toShort())
        buf.putShort(42, totalParts.toShort())
        buf.putInt(44, info.imageCount)

        putResourceHeader(buf, 48, tableSize, 0, tableOffset, tableSize)
        putResourceHeader(buf, 72, xmlSize, 0, xmlOffset, xmlSize)

        // install.wim is never booted, and the boot metadata resource would need
        // an offset inside this part - clear it instead of pointing it somewhere wrong.
        putResourceHeader(buf, 96, 0, 0, 0, 0)
        buf.putInt(120, 0)
        // Integrity table describes the old byte layout, so it must go.
        putResourceHeader(buf, 124, 0, 0, 0, 0)
        return bytes
    }
}
