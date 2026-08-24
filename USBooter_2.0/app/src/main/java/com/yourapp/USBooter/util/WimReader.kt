package com.yourapp.USBooter.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, read-only parser for the Windows Imaging Format (WIM) container that
 * Windows media ships as `sources/install.wim`.
 *
 * Only the *container* is parsed: the 208-byte header, the blob/offset table
 * (the "lookup table") and the XML manifest. Nothing is decompressed, which is
 * exactly what makes an on-device split possible on a phone: every compressed
 * resource can be copied verbatim from the ISO into the split parts.
 *
 * Layout of a WIM (all little endian):
 *
 *   0   char  magic[8]        "MSWIM\0\0\0"
 *   8   u32   cbSize          208
 *   12  u32   dwVersion
 *   16  u32   dwFlags
 *   20  u32   dwCompressionSize (chunk size)
 *   24  byte  gWIMGuid[16]
 *   40  u16   usPartNumber
 *   42  u16   usTotalParts
 *   44  u32   dwImageCount
 *   48  RES   rhOffsetTable   (24 bytes)
 *   72  RES   rhXmlData
 *   96  RES   rhBootMetadata
 *   120 u32   dwBootIndex
 *   124 RES   rhIntegrity
 *   148 byte  unused[60]
 *
 * A resource header (RES, 24 bytes) is a 7-byte size-in-wim, a 1-byte flag
 * field, an 8-byte absolute offset and an 8-byte uncompressed size.
 * The offset table is an array of 50-byte entries: RES + u16 part number +
 * u32 reference count + 20-byte SHA-1 of the uncompressed stream.
 */
object WimReader {

    const val HEADER_SIZE = 208
    const val LOOKUP_ENTRY_SIZE = 50

    // Resource header flags.
    const val RES_FLAG_FREE = 0x01
    const val RES_FLAG_METADATA = 0x02
    const val RES_FLAG_COMPRESSED = 0x04
    const val RES_FLAG_SPANNED = 0x08
    const val RES_FLAG_SOLID = 0x10

    // Header flags.
    const val HDR_FLAG_SPANNED = 0x00000008
    const val HDR_FLAG_WRITE_IN_PROGRESS = 0x00000040

    private val MAGIC = byteArrayOf(0x4D, 0x53, 0x57, 0x49, 0x4D, 0x00, 0x00, 0x00)

    /** One entry of the offset table: a stored (usually compressed) byte range. */
    data class Resource(
        val offsetInWim: Long,
        val sizeInWim: Long,
        val originalSize: Long,
        val flags: Int,
        val partNumber: Int,
        val refCount: Int,
        val hash: ByteArray
    ) {
        val isMetadata: Boolean get() = flags and RES_FLAG_METADATA != 0
        val isSolid: Boolean get() = flags and RES_FLAG_SOLID != 0
        val isFree: Boolean get() = flags and RES_FLAG_FREE != 0
    }

    /** One installable edition inside the WIM, as described by the XML manifest. */
    data class Image(
        val index: Int,
        val name: String,
        val displayName: String,
        val description: String,
        val editionId: String,
        val architecture: String,
        val totalBytes: Long
    ) {
        /** What the user recognises: "Windows 11 Pro". */
        val label: String get() = displayName.ifBlank { name }.ifBlank { "Image $index" }
    }

    data class Info(
        /** The raw 208 header bytes, reused (patched) when parts are written. */
        val headerBytes: ByteArray,
        val version: Int,
        val flags: Int,
        val chunkSize: Int,
        val guid: ByteArray,
        val partNumber: Int,
        val totalParts: Int,
        val imageCount: Int,
        val bootIndex: Int,
        val bootMetadataHeader: ByteArray,
        val xmlBytes: ByteArray,
        val resources: List<Resource>,
        val images: List<Image>
    ) {
        val isAlreadySplit: Boolean get() = totalParts > 1
        val hasSolidResources: Boolean get() = resources.any { it.isSolid }
        val metadataResources: List<Resource> get() = resources.filter { it.isMetadata }
        val largestResourceBytes: Long get() = resources.maxOfOrNull { it.sizeInWim } ?: 0L
    }

    /** Reads a resource header at [pos] inside [buf]. */
    private fun resourceHeaderAt(buf: ByteBuffer, pos: Int): Triple<Long, Int, Pair<Long, Long>> {
        var size = 0L
        for (i in 0 until 7) size = size or ((buf.get(pos + i).toLong() and 0xFF) shl (8 * i))
        val flags = buf.get(pos + 7).toInt() and 0xFF
        val offset = buf.getLong(pos + 8)
        val original = buf.getLong(pos + 16)
        return Triple(size, flags, offset to original)
    }

    /** True when the bytes at the start of [entry] look like a WIM container. */
    fun looksLikeWim(source: ByteReader, entry: IsoEntry): Boolean {
        if (entry.size < HEADER_SIZE) return false
        val head = runCatching { entry.readAt(source, 0, 8) }.getOrNull() ?: return false
        return head.contentEquals(MAGIC)
    }

    /**
     * Parses the container. Returns null when the file is not a WIM at all
     * (an `install.esd`'s outer container is a WIM too, so this still succeeds
     * there - the caller decides what to do with solid resources).
     */
    fun read(source: ByteReader, entry: IsoEntry): Info? {
        if (!looksLikeWim(source, entry)) return null

        val headerBytes = entry.readAt(source, 0, HEADER_SIZE)
        val h = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cbSize = h.getInt(8)
        if (cbSize != HEADER_SIZE) throw IOException("Unsupported WIM header size ($cbSize bytes)")

        val version = h.getInt(12)
        val flags = h.getInt(16)
        val chunkSize = h.getInt(20)
        val guid = headerBytes.copyOfRange(24, 40)
        val partNumber = h.getShort(40).toInt() and 0xFFFF
        val totalParts = h.getShort(42).toInt() and 0xFFFF
        val imageCount = h.getInt(44)
        val bootIndex = h.getInt(120)

        val (tableSize, tableFlags, tableOffsets) = resourceHeaderAt(h, 48)
        val (tableOffset, _) = tableOffsets
        if (tableFlags and (RES_FLAG_COMPRESSED or RES_FLAG_SOLID) != 0) {
            throw IOException(
                "This image stores its WIM offset table compressed, which USBooter cannot rewrite on-device"
            )
        }
        if (tableSize <= 0 || tableSize % LOOKUP_ENTRY_SIZE != 0L) {
            throw IOException("Unexpected WIM offset table size ($tableSize bytes)")
        }
        if (tableSize > 64L * 1024 * 1024) throw IOException("WIM offset table is implausibly large")

        val tableBytes = readExact(source, entry, tableOffset, tableSize.toInt())
        val table = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val resources = ArrayList<Resource>((tableSize / LOOKUP_ENTRY_SIZE).toInt())
        var pos = 0
        while (pos + LOOKUP_ENTRY_SIZE <= tableBytes.size) {
            val (size, resFlags, offsets) = resourceHeaderAt(table, pos)
            val entryPart = table.getShort(pos + 24).toInt() and 0xFFFF
            val refCount = table.getInt(pos + 26)
            val hash = tableBytes.copyOfRange(pos + 30, pos + 50)
            if (resFlags and RES_FLAG_FREE == 0) {
                resources.add(
                    Resource(
                        offsetInWim = offsets.first,
                        sizeInWim = size,
                        originalSize = offsets.second,
                        flags = resFlags,
                        partNumber = entryPart,
                        refCount = refCount,
                        hash = hash
                    )
                )
            }
            pos += LOOKUP_ENTRY_SIZE
        }

        val (xmlSize, xmlFlags, xmlOffsets) = resourceHeaderAt(h, 72)
        val xmlBytes = if (xmlSize in 1..(32L * 1024 * 1024) &&
            xmlFlags and (RES_FLAG_COMPRESSED or RES_FLAG_SOLID) == 0
        ) {
            readExact(source, entry, xmlOffsets.first, xmlSize.toInt())
        } else ByteArray(0)

        return Info(
            headerBytes = headerBytes,
            version = version,
            flags = flags,
            chunkSize = chunkSize,
            guid = guid,
            partNumber = partNumber,
            totalParts = totalParts,
            imageCount = imageCount,
            bootIndex = bootIndex,
            bootMetadataHeader = headerBytes.copyOfRange(96, 120),
            xmlBytes = xmlBytes,
            resources = resources,
            images = parseImages(xmlBytes)
        )
    }

    private fun readExact(source: ByteReader, entry: IsoEntry, offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > entry.size) {
            throw IOException("WIM structure at offset $offset ($length bytes) lies outside the file")
        }
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val take = minOf(1 shl 20, length - done)
            val chunk = entry.readAt(source, offset + done, take)
            System.arraycopy(chunk, 0, out, done, take)
            done += take
        }
        return out
    }

    /** Pulls the human-readable edition list out of the WIM's XML manifest. */
    fun parseImages(xmlBytes: ByteArray): List<Image> {
        if (xmlBytes.isEmpty()) return emptyList()
        val text = decodeXml(xmlBytes)
        val out = mutableListOf<Image>()
        Regex("<IMAGE\\s+INDEX=\"(\\d+)\"(.*?)</IMAGE>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).forEach { match ->
                val index = match.groupValues[1].toIntOrNull() ?: return@forEach
                val body = match.groupValues[2]
                out.add(
                    Image(
                        index = index,
                        name = tag(body, "NAME"),
                        displayName = tag(body, "DISPLAYNAME"),
                        description = tag(body, "DESCRIPTION").ifBlank { tag(body, "DISPLAYDESCRIPTION") },
                        editionId = tag(body, "EDITIONID"),
                        architecture = when (tag(body, "ARCH")) {
                            "0" -> "x86"
                            "9" -> "x64"
                            "12" -> "arm64"
                            else -> ""
                        },
                        totalBytes = tag(body, "TOTALBYTES").toLongOrNull() ?: 0L
                    )
                )
            }
        return out.sortedBy { it.index }
    }

    private fun tag(body: String, name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)
            ?.groupValues?.get(1)?.trim() ?: ""

    /** WIM XML is UTF-16LE with a byte-order mark. */
    private fun decodeXml(bytes: ByteArray): String {
        val hasBom = bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE
        return if (hasBom) String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        else String(bytes, Charsets.UTF_16LE)
    }
}
