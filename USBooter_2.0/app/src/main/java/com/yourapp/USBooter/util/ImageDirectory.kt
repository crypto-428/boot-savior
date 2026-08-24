package com.yourapp.USBooter.util

/**
 * Picks the best file tree an image exposes.
 *
 * A modern install image often carries two (or three) directory structures at
 * once: ISO9660, Joliet and UDF. They are not equivalent - ISO9660/Joliet
 * cannot describe a file of 4 GB or more, which is exactly the case for
 * `sources/install.wim` on Windows 10/11 media. Reading the wrong one is how
 * the app used to end up with a truncated install.wim (or refuse the image
 * altogether), so the tree with the larger, more complete payload wins.
 */
object ImageDirectory {

    data class Result(
        val entries: List<IsoEntry>,
        val volumeLabel: String,
        /** "UDF", "ISO9660" or "none". */
        val format: String,
        /** True when both trees exist and UDF was preferred because it is bigger. */
        val udfPreferred: Boolean
    ) {
        val files: List<IsoEntry> get() = entries.filter { !it.isDirectory }
        val largestFileBytes: Long get() = files.maxOfOrNull { it.size } ?: 0L
        val totalBytes: Long get() = files.sumOf { it.size }
        fun find(path: String): IsoEntry? {
            val target = path.trim('/').lowercase()
            return files.firstOrNull { it.path.trim('/').lowercase() == target }
        }
    }

    fun read(source: ByteReader): Result {
        val iso = Iso9660Reader(source)
        val isoEntries = if (runCatching { iso.parse() }.getOrDefault(false)) {
            runCatching { iso.listAll() }.getOrDefault(emptyList())
        } else emptyList()

        val udf = UdfReader(source)
        val udfEntries = if (runCatching { udf.parse() }.getOrDefault(false)) {
            runCatching { udf.listAll() }.getOrDefault(emptyList())
        } else emptyList()

        val isoBytes = isoEntries.filter { !it.isDirectory }.sumOf { it.size }
        val udfBytes = udfEntries.filter { !it.isDirectory }.sumOf { it.size }

        // UDF wins when it exists and describes at least as much data. That is
        // always true for Windows media, and harmless for Linux hybrids where
        // the two trees agree.
        val preferUdf = udfEntries.isNotEmpty() && (isoEntries.isEmpty() || udfBytes >= isoBytes)

        return when {
            preferUdf -> Result(
                udfEntries,
                udf.volumeLabel.ifBlank { iso.volumeLabel },
                "UDF",
                isoEntries.isNotEmpty()
            )
            isoEntries.isNotEmpty() -> Result(isoEntries, iso.volumeLabel, "ISO9660", false)
            else -> Result(emptyList(), iso.volumeLabel, "none", false)
        }
    }
}
