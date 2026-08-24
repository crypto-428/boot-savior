package com.yourapp.USBooter

import com.yourapp.USBooter.util.ByteReader
import com.yourapp.USBooter.util.IsoEntry
import com.yourapp.USBooter.util.WimReader
import com.yourapp.USBooter.util.WimSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Unit tests for the WIM container parser and the on-device SWM splitter.
 *
 * Every fixture is a *synthetic* WIM built in memory by [SyntheticWim]: a real
 * 208-byte header, deterministic pseudo-resource payloads, a 50-byte-per-entry
 * offset table and a UTF-16LE XML manifest. That is enough to exercise exactly
 * the parts of the format USBooter touches, and it lets the tests cover several
 * "different install.wim images" (single edition, multi edition, huge streams,
 * solid/LZMS, already split) without shipping multi-GB binaries.
 */
class WimSplitterTest {

    // ── Fixture builder ──────────────────────────────────────────────────

    private class ResourceSpec(
        val size: Int,
        val metadata: Boolean = false,
        val solid: Boolean = false,
        val refCount: Int = 1
    )

    private class SyntheticWim(
        val bytes: ByteArray,
        val resourceOffsets: List<Long>,
        val specs: List<ResourceSpec>
    ) {
        val reader: ByteReader = object : ByteReader {
            override val size: Long get() = bytes.size.toLong()
            override fun readAt(offset: Long, length: Int): ByteArray {
                val out = ByteArray(length)
                val from = offset.toInt().coerceIn(0, bytes.size)
                val take = minOf(length, bytes.size - from)
                if (take > 0) System.arraycopy(bytes, from, out, 0, take)
                return out
            }
        }

        fun entry(path: String = "sources/install.wim"): IsoEntry =
            IsoEntry(path = path, isDirectory = false, offset = 0, size = bytes.size.toLong())
    }

    private fun payload(index: Int, size: Int): ByteArray =
        ByteArray(size) { i -> ((index * 31 + i * 7 + (i shr 8)) and 0xFF).toByte() }

    private fun xmlManifest(images: List<Pair<Int, String>>): ByteArray {
        val text = buildString {
            append("<WIM><TOTALBYTES>1234</TOTALBYTES>")
            images.forEach { (index, name) ->
                append("<IMAGE INDEX=\"$index\">")
                append("<NAME>$name</NAME><DISPLAYNAME>$name</DISPLAYNAME>")
                append("<DESCRIPTION>$name desc</DESCRIPTION>")
                append("<WINDOWS><ARCH>9</ARCH><EDITIONID>${name.replace(" ", "")}</EDITIONID></WINDOWS>")
                append("<TOTALBYTES>${index * 1000}</TOTALBYTES>")
                append("</IMAGE>")
            }
            append("</WIM>")
        }
        val body = text.toByteArray(Charsets.UTF_16LE)
        return byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body
    }

    private fun putRes(
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

    private fun buildWim(
        specs: List<ResourceSpec>,
        images: List<Pair<Int, String>> = listOf(1 to "Windows 11 Pro"),
        partNumber: Int = 1,
        totalParts: Int = 1,
        tableFlags: Int = 0,
        xml: ByteArray? = null
    ): SyntheticWim {
        val xmlBytes = xml ?: xmlManifest(images)
        val offsets = mutableListOf<Long>()
        var offset = WimReader.HEADER_SIZE.toLong()
        val payloads = specs.mapIndexed { index, spec ->
            offsets.add(offset)
            offset += spec.size
            payload(index, spec.size)
        }
        val tableOffset = offset
        val tableSize = specs.size * WimReader.LOOKUP_ENTRY_SIZE
        offset += tableSize
        val xmlOffset = offset
        offset += xmlBytes.size

        val bytes = ByteArray(offset.toInt())
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Header.
        "MSWIM\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 0)
        buf.putInt(8, WimReader.HEADER_SIZE)
        buf.putInt(12, 0x000D_0000)
        buf.putInt(16, 0x00000002)
        buf.putInt(20, 32 * 1024)
        ByteArray(16) { (it + 1).toByte() }.copyInto(bytes, 24)
        buf.putShort(40, partNumber.toShort())
        buf.putShort(42, totalParts.toShort())
        buf.putInt(44, images.size)
        putRes(buf, 48, tableSize.toLong(), tableFlags, tableOffset, tableSize.toLong())
        putRes(buf, 72, xmlBytes.size.toLong(), 0, xmlOffset, xmlBytes.size.toLong())
        putRes(buf, 96, 0, 0, 0, 0)
        buf.putInt(120, 0)
        putRes(buf, 124, 0, 0, 0, 0)

        // Payloads.
        payloads.forEachIndexed { index, data -> data.copyInto(bytes, offsets[index].toInt()) }

        // Offset table.
        specs.forEachIndexed { index, spec ->
            val base = tableOffset.toInt() + index * WimReader.LOOKUP_ENTRY_SIZE
            var flags = 0
            if (spec.metadata) flags = flags or WimReader.RES_FLAG_METADATA
            if (spec.solid) flags = flags or WimReader.RES_FLAG_SOLID
            putRes(buf, base, spec.size.toLong(), flags, offsets[index], spec.size.toLong())
            buf.putShort(base + 24, partNumber.toShort())
            buf.putInt(base + 26, spec.refCount)
            sha1(payloads[index]).copyInto(bytes, base + 30)
        }

        xmlBytes.copyInto(bytes, xmlOffset.toInt())
        return SyntheticWim(bytes, offsets, specs)
    }

    private fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    /** Reads a whole generated part out of the plan, in small chunks. */
    private fun readPart(plan: WimSplitter.Plan, index: Int): ByteArray {
        val part = plan.parts[index]
        val out = ByteArray(part.sizeBytes.toInt())
        var done = 0
        while (done < out.size) {
            val take = minOf(4096, out.size - done)
            plan.read(index, done.toLong(), take).copyInto(out, done)
            done += take
        }
        return out
    }

    private fun readerFor(bytes: ByteArray): Pair<ByteReader, IsoEntry> {
        val reader = object : ByteReader {
            override val size: Long get() = bytes.size.toLong()
            override fun readAt(offset: Long, length: Int): ByteArray {
                val out = ByteArray(length)
                val from = offset.toInt().coerceIn(0, bytes.size)
                val take = minOf(length, bytes.size - from)
                if (take > 0) System.arraycopy(bytes, from, out, 0, take)
                return out
            }
        }
        return reader to IsoEntry("install.swm", false, 0, bytes.size.toLong())
    }

    // ── WIM parsing ──────────────────────────────────────────────────────

    @Test
    fun `parses header resources and editions of a multi-edition image`() {
        val wim = buildWim(
            specs = listOf(
                ResourceSpec(4096, metadata = true),
                ResourceSpec(4096, metadata = true),
                ResourceSpec(64 * 1024),
                ResourceSpec(32 * 1024, refCount = 3)
            ),
            images = listOf(1 to "Windows 11 Home", 2 to "Windows 11 Pro")
        )
        val info = WimReader.read(wim.reader, wim.entry())
        assertNotNull(info)
        info!!
        assertEquals(1, info.partNumber)
        assertEquals(1, info.totalParts)
        assertEquals(2, info.imageCount)
        assertEquals(4, info.resources.size)
        assertEquals(2, info.metadataResources.size)
        assertEquals(false, info.isAlreadySplit)
        assertEquals(false, info.hasSolidResources)
        assertEquals((64 * 1024).toLong(), info.largestResourceBytes)
        assertEquals(listOf(1, 2), info.images.map { it.index })
        assertEquals(listOf("Windows 11 Home", "Windows 11 Pro"), info.images.map { it.label })
        assertEquals("x64", info.images[0].architecture)
        assertEquals(3, info.resources.first { it.refCount == 3 }.refCount)
    }

    @Test
    fun `rejects data that is not a WIM container`() {
        val bytes = ByteArray(4096) { 0x41 }
        val (reader, entry) = readerFor(bytes)
        assertNull(WimReader.read(reader, entry))
    }

    @Test
    fun `detects a solid ESD-style container`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(8192, solid = true)))
        val info = WimReader.read(wim.reader, wim.entry())!!
        assertTrue(info.hasSolidResources)
    }

    // ── Splitting ────────────────────────────────────────────────────────

    @Test
    fun `single small image produces one part that stays a valid WIM`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(16 * 1024)))
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 1L shl 20)
        assertEquals(1, plan.parts.size)
        assertEquals("install.swm", plan.parts[0].name)
        assertEquals(listOf("sources/install.swm"), plan.partPaths)
        assertEquals("sources", plan.targetDirectory)

        val (reader, entry) = readerFor(readPart(plan, 0))
        val info = WimReader.read(reader, entry)!!
        assertEquals(1, info.partNumber)
        assertEquals(1, info.totalParts)
        assertTrue(info.flags and WimReader.HDR_FLAG_SPANNED != 0)
        assertEquals(2, info.resources.size)
    }

    @Test
    fun `large image spans several parts each under the cap`() {
        val specs = listOf(ResourceSpec(8 * 1024, metadata = true)) +
            List(9) { ResourceSpec(200 * 1024) }
        val wim = buildWim(specs, images = listOf(1 to "Windows 11 Pro"))
        val cap = 512L * 1024
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = cap)

        assertTrue("expected a spanned set, got ${plan.parts.size}", plan.parts.size >= 3)
        plan.parts.forEach { assertTrue("${it.name} is ${it.sizeBytes} bytes", it.sizeBytes <= cap) }
        assertEquals(
            listOf("install.swm") + (2..plan.parts.size).map { "install$it.swm" },
            plan.parts.map { it.name }
        )
        assertEquals(specs.size, plan.parts.sumOf { it.resourceCount })
        assertEquals(plan.parts.sumOf { it.sizeBytes }, plan.totalBytes)

        // Every part is a parsable WIM, numbered and flagged as spanned.
        plan.parts.indices.forEach { index ->
            val (reader, entry) = readerFor(readPart(plan, index))
            val info = WimReader.read(reader, entry)!!
            assertEquals(index + 1, info.partNumber)
            assertEquals(plan.parts.size, info.totalParts)
            assertEquals(1, info.imageCount)
            assertTrue(info.flags and WimReader.HDR_FLAG_SPANNED != 0)
            assertEquals(plan.parts[index].resourceCount, info.resources.size)
            // Metadata always lives in part 1.
            if (index == 0) assertEquals(1, info.metadataResources.size)
            else assertEquals(0, info.metadataResources.size)
        }
    }

    @Test
    fun `every resource is copied verbatim and its SHA-1 survives the split`() {
        val specs = listOf(ResourceSpec(4 * 1024, metadata = true)) +
            List(6) { ResourceSpec(150 * 1024, refCount = it + 1) }
        val wim = buildWim(specs)
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 400L * 1024)

        val expected = specs.indices.associate { index ->
            val data = payload(index, specs[index].size)
            sha1(data).toList() to data
        }

        var seen = 0
        val refCounts = mutableListOf<Int>()
        plan.parts.indices.forEach { partIndex ->
            val bytes = readPart(plan, partIndex)
            val (reader, entry) = readerFor(bytes)
            val info = WimReader.read(reader, entry)!!
            info.resources.forEach { res ->
                val stored = bytes.copyOfRange(
                    res.offsetInWim.toInt(),
                    (res.offsetInWim + res.sizeInWim).toInt()
                )
                val source = expected[res.hash.toList()]
                assertNotNull("resource hash not found in source WIM", source)
                assertTrue("resource bytes differ after split", stored.contentEquals(source))
                assertTrue("SHA-1 does not match its bytes", sha1(stored).contentEquals(res.hash))
                assertEquals(partIndex + 1, res.partNumber)
                refCounts.add(res.refCount)
                seen++
            }
        }
        assertEquals(specs.size, seen)
        assertEquals(specs.map { it.refCount }.sorted(), refCounts.sorted())
    }

    @Test
    fun `XML manifest and editions are repeated in every part`() {
        val specs = listOf(ResourceSpec(4 * 1024, metadata = true)) + List(5) { ResourceSpec(120 * 1024) }
        val wim = buildWim(specs, images = listOf(1 to "Windows 11 Home", 2 to "Windows 11 Pro"))
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 300L * 1024)
        assertEquals(listOf("Windows 11 Home", "Windows 11 Pro"), plan.images.map { it.label })
        plan.parts.indices.forEach { index ->
            val (reader, entry) = readerFor(readPart(plan, index))
            val info = WimReader.read(reader, entry)!!
            assertEquals(2, info.images.size)
            assertEquals(listOf("Windows 11 Home", "Windows 11 Pro"), info.images.map { it.label })
        }
    }

    @Test
    fun `boot metadata and integrity headers are cleared in generated parts`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(8192)))
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 1L shl 20)
        val bytes = readPart(plan, 0)
        assertEquals(0, bytes.copyOfRange(96, 120).count { it != 0.toByte() })
        assertEquals(0, bytes.copyOfRange(124, 148).count { it != 0.toByte() })
    }

    @Test
    fun `chunked reads match a single whole-part read`() {
        val specs = listOf(ResourceSpec(2048, metadata = true)) + List(4) { ResourceSpec(70 * 1024) }
        val wim = buildWim(specs)
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 200L * 1024)
        plan.parts.indices.forEach { index ->
            val whole = plan.read(index, 0, plan.parts[index].sizeBytes.toInt())
            assertTrue(whole.contentEquals(readPart(plan, index)))
        }
    }

    @Test
    fun `reading past the end of a part fails instead of returning zeroes`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(4096)))
        val plan = WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 1L shl 20)
        val size = plan.parts[0].sizeBytes
        try {
            plan.read(0, size - 4, 64)
            throw AssertionError("expected an IOException for an out-of-range read")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("outside"))
        }
    }

    // ── Rejected images ──────────────────────────────────────────────────

    @Test
    fun `refuses an already split set`() {
        val wim = buildWim(
            listOf(ResourceSpec(2048, metadata = true), ResourceSpec(4096)),
            partNumber = 1,
            totalParts = 3
        )
        assertNotSplittable(wim, "already part")
    }

    @Test
    fun `refuses solid LZMS resources`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(4096, solid = true)))
        assertNotSplittable(wim, "solid")
    }

    @Test
    fun `refuses an image without an XML manifest`() {
        val wim = buildWim(
            listOf(ResourceSpec(2048, metadata = true), ResourceSpec(4096)),
            xml = ByteArray(0)
        )
        assertNotSplittable(wim, "XML")
    }

    @Test
    fun `refuses a single resource that is larger than one part`() {
        val wim = buildWim(listOf(ResourceSpec(2048, metadata = true), ResourceSpec(300 * 1024)))
        assertNotSplittable(wim, "cannot fit")
    }

    @Test
    fun `refuses a compressed offset table`() {
        val wim = buildWim(
            listOf(ResourceSpec(2048, metadata = true), ResourceSpec(4096)),
            tableFlags = WimReader.RES_FLAG_COMPRESSED
        )
        try {
            WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 1L shl 20)
            throw AssertionError("expected the compressed offset table to be rejected")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("offset table"))
        }
    }

    private fun assertNotSplittable(wim: SyntheticWim, needle: String) {
        try {
            WimSplitter.plan(wim.reader, wim.entry(), maxPartBytes = 128L * 1024)
            throw AssertionError("expected NotSplittable containing \"$needle\"")
        } catch (e: WimSplitter.NotSplittable) {
            assertTrue("message was: ${e.message}", e.message!!.contains(needle))
        }
    }
}
