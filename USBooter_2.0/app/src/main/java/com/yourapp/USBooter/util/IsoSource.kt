package com.yourapp.USBooter.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Random-access read-only view of the ISO the user picked through the Storage
 * Access Framework. SAF gives us a file descriptor, so we can seek instead of
 * having to buffer a multi-GB image in memory.
 */
class IsoSource private constructor(
    private val closeables: List<Closeable>,
    private val channel: java.nio.channels.FileChannel,
    val displayName: String
) : Closeable, ByteReader {

    override val size: Long = channel.size()

    private val lock = Any()

    /**
     * Reads exactly [length] bytes at [offset].
     *
     * A short SAF/content-provider read must never be treated as zero-filled ISO
     * data: doing that makes the writer and verifier hash invented zeroes rather
     * than the selected image. Only a request that genuinely extends past EOF is
     * padded, which is used for the final physical block of a raw clone.
     */
    override fun readAt(offset: Long, length: Int): ByteArray = synchronized(lock) {
        require(offset >= 0) { "offset must not be negative" }
        require(length >= 0) { "length must not be negative" }
        val out = ByteArray(length)
        if (offset >= size) return out
        val required = minOf(length.toLong(), size - offset).toInt()
        val buffer = ByteBuffer.wrap(out)
        buffer.limit(required)
        var position = offset
        var zeroReads = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) throw IOException("Unexpected end of ISO at byte $position")
            if (read == 0) {
                zeroReads++
                if (zeroReads >= 8) {
                    throw IOException("ISO provider stopped responding at byte $position")
                }
                Thread.yield()
                continue
            }
            zeroReads = 0
            position += read
        }
        if (position - offset != required.toLong()) {
            throw IOException("Short ISO read at byte $offset: ${position - offset}/$required")
        }
        return out
    }

    override fun close() {
        closeables.forEach { runCatching { it.close() } }
    }

    companion object {
        fun open(context: Context, uri: Uri): IsoSource {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open the selected ISO")
            val stream = FileInputStream(pfd.fileDescriptor)
            return IsoSource(listOf(stream, pfd), stream.channel, queryDisplayName(context, uri))
        }

        fun queryDisplayName(context: Context, uri: Uri): String {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
                }
            return uri.lastPathSegment ?: "image.iso"
        }
    }
}
