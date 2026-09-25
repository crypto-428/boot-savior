package com.yourapp.USBooter.util

/**
 * FAT16 and FAT12 formatter, for small drives and old PCs/devices.
 *
 * FAT12 holds up to 4084 clusters (volumes up to ~128 MB), FAT16 up to 65524
 * (volumes up to ~4 GB with 64 KB clusters). Sizes outside that are refused
 * with a message instead of producing an unreadable volume.
 */
object FatLegacyFormatter {

    private const val ROOT_ENTRIES = 512

    /** Returns the largest volume, in sectors, this format can hold. */
    fun maxSectors(fat12: Boolean, blockSize: Int): Long =
        (if (fat12) 4084L else 65524L) * 128 * 512 / blockSize

    fun format(device: BlockWriter, start: Long, sectors: Long, label: String, fat12: Boolean) {
        val bps = device.blockSize
        val maxClusters = if (fat12) 4084L else 65524L
        val minClusters = if (fat12) 1L else 4085L
        val rootSectors = (ROOT_ENTRIES * 32 + bps - 1) / bps
        var spc = 1
        var fatSz = 0L
        var clusters = 0L
        while (true) {
            fatSz = 1
            repeat(4) {
                clusters = (sectors - 1 - rootSectors - 2 * fatSz) / spc
                val bytes = if (fat12) ((clusters + 2) * 3 + 1) / 2 else (clusters + 2) * 2
                fatSz = (bytes + bps - 1) / bps
            }
            clusters = (sectors - 1 - rootSectors - 2 * fatSz) / spc
            if (clusters <= maxClusters || spc >= 128) break
            spc *= 2
        }
        require(clusters in minClusters..maxClusters) {
            if (clusters > maxClusters) "This partition is too large for ${if (fat12) "FAT12" else "FAT16"}. Choose FAT32 or exFAT instead."
            else "This partition is too small for FAT16. Choose FAT12 instead."
        }

        val boot = ByteArray(bps)
        boot[0] = 0xEB.toByte(); boot[1] = 0x3C; boot[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(Charsets.US_ASCII).copyInto(boot, 3)
        put16(boot, 11, bps); boot[13] = spc.toByte(); put16(boot, 14, 1); boot[16] = 2
        put16(boot, 17, ROOT_ENTRIES)
        if (sectors < 65536) put16(boot, 19, sectors.toInt()) else put32(boot, 32, sectors)
        boot[21] = 0xF8.toByte(); put16(boot, 22, fatSz.toInt()); put16(boot, 24, 63); put16(boot, 26, 255)
        put32(boot, 28, start); boot[36] = 0x80.toByte(); boot[38] = 0x29
        put32(boot, 39, (System.nanoTime() xor System.currentTimeMillis()) and 0xFFFFFFFFL)
        val name = clean(label)
        name.toByteArray(Charsets.US_ASCII).copyInto(boot, 43)
        (if (fat12) "FAT12   " else "FAT16   ").toByteArray(Charsets.US_ASCII).copyInto(boot, 54)
        boot[bps - 2] = 0x55; boot[bps - 1] = 0xAA.toByte()
        if (bps > 512) { boot[510] = 0x55; boot[511] = 0xAA.toByte() }
        device.writeBlocks(start, boot)

        val fat = ByteArray((fatSz * bps).toInt())
        fat[0] = 0xF8.toByte(); fat[1] = 0xFF.toByte(); fat[2] = 0xFF.toByte()
        if (!fat12) fat[3] = 0xFF.toByte()
        device.writeBlocks(start + 1, fat)
        device.writeBlocks(start + 1 + fatSz, fat)

        val root = ByteArray(rootSectors * bps)
        if (name.isNotBlank() && name.trim() != "NO NAME") {
            name.toByteArray(Charsets.US_ASCII).copyInto(root, 0)
            root[11] = 0x08
        }
        device.writeBlocks(start + 1 + 2 * fatSz, root)
        // Clear the first cluster so no stale data looks like a directory.
        device.writeBlocks(start + 1 + 2 * fatSz + rootSectors, ByteArray(spc * bps))
    }

    private class Geo(val bps: Int, val spc: Int, val reserved: Int, val fats: Int, val fatSz: Long,
                      val rootSectors: Int, val total: Long, val fat12: Boolean) {
        val dataStart get() = reserved + fats * fatSz + rootSectors
        /** Clusters the FAT tables have room for (not the volume). */
        val fatCapacity get() = (if (fat12) fatSz * bps * 2 / 3 else fatSz * bps / 2) - 2
        fun clustersFor(sectors: Long) = (sectors - dataStart) / spc
    }

    private fun geo(boot: ByteArray): Geo? {
        val bps = le16(boot, 11); val spc = boot[13].toInt() and 0xFF
        if (bps !in 512..4096 || spc == 0) return null
        val small = le16(boot, 19).toLong()
        val total = if (small > 0) small else le32(boot, 32)
        val t = String(boot, 54, 5, Charsets.US_ASCII)
        val fat12 = t == "FAT12"
        if (!fat12 && t != "FAT16") return null
        return Geo(bps, spc, le16(boot, 14), boot[16].toInt() and 0xFF, le16(boot, 22).toLong(),
            (le16(boot, 17) * 32 + bps - 1) / bps, total, fat12)
    }

    private fun entry(fat: ByteArray, n: Long, fat12: Boolean): Int = if (fat12) {
        val o = (n * 3 / 2).toInt()
        val v = le16(fat, o)
        if (n % 2 == 0L) v and 0xFFF else v shr 4
    } else le16(fat, (n * 2).toInt())

    private fun readFat(device: BlockDevice, start: Long, g: Geo): ByteArray {
        val per = g.bps / device.blockSize
        return device.readBlocks(start + g.reserved.toLong() * per, (g.fatSz * per).toInt())
    }

    /** Smallest size (device sectors) keeping every used cluster, or null if not FAT12/16. */
    fun minimumSectors(device: BlockDevice, start: Long, boot: ByteArray): Long? = runCatching {
        val g = geo(boot) ?: return null
        val fat = readFat(device, start, g)
        val clusters = minOf(g.clustersFor(g.total), g.fatCapacity)
        var last = 1L
        for (c in 2 until clusters + 2) if (entry(fat, c, g.fat12) != 0) last = c
        val minClusters = maxOf(last - 1, if (g.fat12) 1L else 4085L)
        (g.dataStart + minClusters * g.spc) * (g.bps / device.blockSize)
    }.getOrNull()

    /** Largest size (device sectors) the existing FAT tables can address. */
    fun maximumSectors(boot: ByteArray, blockSize: Int): Long? {
        val g = geo(boot) ?: return null
        val cap = minOf(g.fatCapacity, if (g.fat12) 4084L else 65524L)
        return (g.dataStart + cap * g.spc) * (g.bps / blockSize)
    }

    /**
     * Shrinks or grows a FAT12/FAT16 volume in place by rewriting its size. Nothing
     * moves: shrinking is refused below the last used cluster, growing stops at what
     * the FAT tables can address. Returns an error message, or null on success.
     */
    fun resize(device: BlockDevice, start: Long, newSectors: Long, boot: ByteArray): String? {
        val g = geo(boot) ?: return "This is not a FAT16 or FAT12 volume"
        val per = g.bps / device.blockSize
        val min = minimumSectors(device, start, boot) ?: return "The FAT table could not be read"
        val max = maximumSectors(boot, device.blockSize)!!
        if (newSectors < min) return "Files are stored up to sector $min, so the volume cannot be shrunk to $newSectors sectors"
        val target = minOf(newSectors, max) / per
        val b = boot.copyOf()
        if (target < 65536) { put16(b, 19, target.toInt()); put32(b, 32, 0) } else { put16(b, 19, 0); put32(b, 32, target) }
        return runCatching { device.writeBlocks(start, b); null }.getOrElse { "The boot sector could not be written: ${it.message}" }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)

    private fun clean(label: String): String {

        val s = label.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it in " _-" }.take(11)
        return (s.ifBlank { "NO NAME" }).padEnd(11)
    }

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
}
