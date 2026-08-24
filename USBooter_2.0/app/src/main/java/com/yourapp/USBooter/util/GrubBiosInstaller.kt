package com.yourapp.USBooter.util

import android.content.res.AssetManager
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.text.Charsets

/**
 * Installs a real GRUB 2 (i386-pc) bootloader so the stick starts on legacy
 * BIOS machines, without touching the partition table that [Mbr]/[Gpt] wrote.
 *
 * This is the "medium" approach used by Rufus/Ventoy and the reason a plain
 * chainloading MBR was never enough: a BIOS only loads 512 bytes, and those 512
 * bytes have to be able to find a kernel. GRUB solves that in two stages:
 *
 *  - `boot.img`  (512 B) goes into sector 0, keeping bytes 0x1B8..0x1FF (disk
 *    signature, the four partition entries and the 0x55AA signature) exactly as
 *    the partitioner left them. It only knows one thing: the LBA of core.img.
 *  - `core.img`  (~185 KB) is written into the MBR gap (LBA 1 .. partition
 *    start). It contains the filesystem drivers (FAT/NTFS/exFAT/ISO9660/ext2),
 *    `search`, `linux`, `chain`, `ntldr` and `syslinuxcfg`, so it can mount the
 *    stick and run `/usbooter/grub/grub.cfg`.
 *
 * Both images are shipped prebuilt in `assets/boot/` and are already patched:
 * boot.img points at LBA 1 and core.img's block list points at LBA 2, so the
 * installer just has to place them - no relocation maths at flash time.
 *
 * Because a silently corrupted asset (the classic case: the binary re-saved as
 * UTF-8 text, which inflates boot.img from 512 to 808 bytes) produces a drive
 * that fails only at boot time, every byte is checked twice: the asset is
 * hashed before anything is written, and the sectors are read back afterwards.
 */
object GrubBiosInstaller {

    private const val TAG = "GrubBiosInstaller"
    const val BOOT_IMG_ASSET = "boot/grub_boot.img"
    const val CORE_IMG_ASSET = "boot/grub_core.img"

    /** Exact size of an x86 boot sector. boot.img is never anything else. */
    const val BOOT_IMG_SIZE = 512

    /** SHA-256 of the bundled, already-patched GRUB 2.12 i386-pc images. */
    const val BOOT_IMG_SHA256 = "6343b7e9f06388566ea5b6e8a3535fbaec1f695a0b3793caee5386237d4d3450"
    const val CORE_IMG_SHA256 = "5155e0ef966d06db276b29dbb68a7dfcae8bfd6658ec1746525dd8ea524b69f0"
    const val CORE_IMG_SIZE = 188928

    /** Where grub.cfg lives on the target partition; also what core.img searches for. */
    const val CONFIG_DIR = "usbooter/grub"
    const val CONFIG_PATH = "usbooter/grub/grub.cfg"

    /** Sectors the embedded core image needs, so callers can validate the MBR gap. */
    fun requiredGapSectors(assets: AssetManager, blockSize: Int): Long {
        val size = loadImage(assets, CORE_IMG_ASSET, CORE_IMG_SIZE, CORE_IMG_SHA256).size.toLong()
        return 1 + (size + blockSize - 1) / blockSize
    }

    /**
     * Writes boot.img over the boot-code area of sector 0 and core.img straight
     * after it. Must run *after* the partition table exists, because sector 0 is
     * read back and only its first 440 bytes are replaced.
     *
     * [progress] receives short human-readable step descriptions so the UI can
     * show which part of the bootloader install is running.
     */
    fun install(device: UsbBulkStorageDevice, assets: AssetManager, progress: (String) -> Unit = {}) {
        val blockSize = device.blockSize

        // ── 1. Validate the bundled assets before a single byte is written ──
        progress("Checking the bundled GRUB images")
        val bootImg = loadImage(assets, BOOT_IMG_ASSET, BOOT_IMG_SIZE, BOOT_IMG_SHA256)
        val coreImg = loadImage(assets, CORE_IMG_ASSET, CORE_IMG_SIZE, CORE_IMG_SHA256)
        validateBootImg(bootImg)
        validateCoreImg(coreImg)
        FlashReport.checksum(BOOT_IMG_ASSET, sha256(bootImg))
        FlashReport.checksum(CORE_IMG_ASSET, sha256(coreImg))
        FlashReport.step("Bundled GRUB assets validated", "${bootImg.size} B boot.img, ${coreImg.size} B core.img")

        val coreSectors = (coreImg.size + blockSize - 1) / blockSize
        val firstPartitionStart = firstPartitionStartLba(device)
        if (firstPartitionStart in 1 until (coreSectors + 1L)) {
            throw IOException(
                "The first partition starts at LBA $firstPartitionStart, but GRUB needs " +
                    "${coreSectors + 1} sectors of free space before it. Re-create the layout with 1 MiB alignment."
            )
        }

        // ── 2. Sector 0: keep the partition table, replace only the boot code ──
        progress("Writing the GRUB boot sector (LBA 0)")
        val sector0 = device.readBlocks(0, 1)
        if (sector0.size < blockSize) throw IOException(
            "Read back ${sector0.size} bytes of sector 0, expected $blockSize - the drive did not answer correctly"
        )
        System.arraycopy(bootImg, 0, sector0, 0, 0x1B8)
        sector0[510] = 0x55
        sector0[511] = 0xAA.toByte()
        device.writeBlocks(0, sector0)

        // ── 3. Sectors 1..n: core.img, padded to a whole number of blocks ──
        progress("Writing GRUB core.img ($coreSectors sectors from LBA 1)")
        val padded = if (coreImg.size % blockSize == 0) coreImg else coreImg.copyOf(coreSectors * blockSize)
        device.writeBlocks(1, padded)
        device.synchronizeCache()

        // ── 4. Read every written sector back and compare ──
        progress("Verifying the bootloader on the drive")
        verifyInstall(device, sector0, padded, coreSectors)

        Log.i(TAG, "GRUB i386-pc installed and verified: core.img at LBA 1, $coreSectors sectors")
    }

    /**
     * Re-reads LBA 0 and the core.img sectors and compares them byte for byte
     * with what was just written. Returns normally only when the drive really
     * holds the patched boot sector and the full core image.
     */
    private fun verifyInstall(
        device: UsbBulkStorageDevice,
        expectedSector0: ByteArray,
        expectedCore: ByteArray,
        coreSectors: Int
    ) {
        val blockSize = device.blockSize

        val back0 = device.readBlocks(0, 1)
        if (back0.size < blockSize) throw IOException(
            "Boot sector read-back returned ${back0.size} bytes, expected $blockSize"
        )
        val bootMismatch = (0 until 0x1B8).firstOrNull { back0[it] != expectedSector0[it] }
        if (bootMismatch != null) FlashReport.mismatch(
            "GRUB boot sector", 0, bootMismatch.toLong(),
            "0x" + hex(expectedSector0[bootMismatch]), "0x" + hex(back0[bootMismatch])
        )
        if (bootMismatch != null) throw IOException(
            "The GRUB boot sector did not read back correctly: byte $bootMismatch at LBA 0 is " +
                "0x${hex(back0[bootMismatch])}, expected 0x${hex(expectedSector0[bootMismatch])}. " +
                "The drive silently discarded the write."
        )
        if (back0[510] != 0x55.toByte() || back0[511] != 0xAA.toByte()) throw IOException(
            "The boot sector on the drive has no 0x55AA signature after writing " +
                "(read 0x${hex(back0[510])}${hex(back0[511])})"
        )
        // The partition table must have survived untouched.
        val tableMismatch = (0x1B8 until 510).firstOrNull { back0[it] != expectedSector0[it] }
        if (tableMismatch != null) throw IOException(
            "The partition table was altered while installing GRUB (byte $tableMismatch at LBA 0)"
        )

        // core.img is compared in 256-sector chunks so a big image never needs
        // to be held twice in memory beyond that window.
        var lba = 1L
        var offset = 0
        while (offset < expectedCore.size) {
            val sectors = minOf(256, coreSectors - offset / blockSize)
            val chunk = device.readBlocks(lba, sectors)
            val length = sectors * blockSize
            if (chunk.size < length) throw IOException(
                "core.img read-back at LBA $lba returned ${chunk.size} bytes, expected $length"
            )
            for (i in 0 until length) {
                if (chunk[i] != expectedCore[offset + i]) {
                    val badLba = lba + i / blockSize
                    FlashReport.mismatch(
                        "GRUB core.img", badLba, (offset + i).toLong(),
                        "0x" + hex(expectedCore[offset + i]), "0x" + hex(chunk[i])
                    )
                    throw IOException(
                        "GRUB core.img did not read back correctly: byte ${offset + i} " +
                            "(LBA $badLba, offset ${i % blockSize}) is 0x${hex(chunk[i])}, " +
                            "expected 0x${hex(expectedCore[offset + i])}. The drive may be faulty or write-cached."
                    )
                }
            }
            offset += length
            lba += sectors
        }
    }

    /**
     * Dry-run entry point: checks the bundled images without touching a drive.
     * Returns a human-readable summary; throws with the exact problem if an
     * asset is missing, the wrong size, or fails its checksum.
     */
    fun validateAssets(assets: AssetManager): String {
        val bootImg = loadImage(assets, BOOT_IMG_ASSET, BOOT_IMG_SIZE, BOOT_IMG_SHA256)
        val coreImg = loadImage(assets, CORE_IMG_ASSET, CORE_IMG_SIZE, CORE_IMG_SHA256)
        validateBootImg(bootImg)
        validateCoreImg(coreImg)
        FlashReport.checksum(BOOT_IMG_ASSET, sha256(bootImg))
        FlashReport.checksum(CORE_IMG_ASSET, sha256(coreImg))
        return "boot.img ${bootImg.size} B and core.img ${coreImg.size} B match their expected SHA-256"
    }

    private fun validateBootImg(img: ByteArray) {
        if (img.size != BOOT_IMG_SIZE) throw IOException(
            "Bundled asset $BOOT_IMG_ASSET is ${img.size} bytes, expected exactly $BOOT_IMG_SIZE " +
                "(one 512-byte boot sector). The asset was corrupted in the build - most often by " +
                "re-saving the binary as text, which re-encodes every byte above 0x7F."
        )
        if (img[510] != 0x55.toByte() || img[511] != 0xAA.toByte()) throw IOException(
            "Bundled asset $BOOT_IMG_ASSET ends with 0x${hex(img[510])}${hex(img[511])} instead of 0x55AA, " +
                "so it is not a valid boot sector"
        )
        val actual = sha256(img)
        if (actual != BOOT_IMG_SHA256) throw IOException(
            "Bundled asset $BOOT_IMG_ASSET failed its checksum: read ${img.size} bytes with " +
                "SHA-256 $actual, expected $BOOT_IMG_SHA256. Nothing was written to the drive."
        )
    }

    private fun validateCoreImg(img: ByteArray) {
        if (img.size != CORE_IMG_SIZE) throw IOException(
            "Bundled asset $CORE_IMG_ASSET is ${img.size} bytes, expected $CORE_IMG_SIZE - " +
                "the asset is truncated or corrupted"
        )
        val actual = sha256(img)
        if (actual != CORE_IMG_SHA256) throw IOException(
            "Bundled asset $CORE_IMG_ASSET failed its checksum: read ${img.size} bytes with " +
                "SHA-256 $actual, expected $CORE_IMG_SHA256. Nothing was written to the drive."
        )
    }

    /**
     * Loads one bundled image, with an automatic repair path.
     *
     * The binary asset is the primary copy, but a build pipeline that treats
     * binary images in `assets/boot/` as text re-encodes them (512 B boot.img
     * producing an APK that can never install GRUB. So every image also ships as
     * a pure-ASCII Base64 twin (`<name>.b64`) that no text tool can damage: when
     * the binary does not match its expected SHA-256 we transparently fall back
     * to decoding the Base64 copy, and only fail if that is broken too.
     */
    private fun loadImage(
        assets: AssetManager,
        name: String,
        expectedSize: Int,
        expectedSha: String
    ): ByteArray {
        val binary = runCatching { readAsset(assets, name) }.getOrNull()
        if (binary != null && binary.size == expectedSize && sha256(binary) == expectedSha) return binary

        val b64Name = "$name.b64"
        val decoded = runCatching {
            val text = readAsset(assets, b64Name).toString(Charsets.US_ASCII).filterNot { it.isWhitespace() }
            android.util.Base64.decode(text, android.util.Base64.DEFAULT)
        }.getOrNull()

        if (decoded != null && decoded.size == expectedSize && sha256(decoded) == expectedSha) {
            val why = if (binary == null) "missing" else "${binary.size} B, SHA-256 ${sha256(binary)}"
            Log.w(TAG, "Recovered $name from $b64Name (binary asset was $why)")
            FlashReport.step(
                "Recovered $name from its Base64 copy",
                "The binary asset in the APK was $why - the text-safe copy matched $expectedSha"
            )
            return decoded
        }

        throw IOException(
            "Bundled asset $name is unusable: the binary copy is " +
                (if (binary == null) "missing" else "${binary.size} bytes (expected $expectedSize) with SHA-256 ${sha256(binary)}") +
                ", and the text-safe copy $b64Name is " +
                (if (decoded == null) "missing or not valid Base64" else "${decoded.size} bytes with SHA-256 ${sha256(decoded)}") +
                ". Expected $expectedSize bytes, SHA-256 $expectedSha. Rebuild the APK without re-encoding assets/boot/*.img as text - nothing was written to the drive."
        )
    }

    private fun readAsset(assets: AssetManager, name: String): ByteArray = try {
        assets.open(name).use { it.readBytes() }
    } catch (e: IOException) {
        throw IOException("Bundled asset $name could not be read from the APK: ${e.message}", e)
    }


    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun hex(b: Byte) = "%02X".format(b)

    /** Lowest non-empty MBR partition start, or -1 when the table is GPT/empty. */
    private fun firstPartitionStartLba(device: UsbBulkStorageDevice): Long {
        val mbr = device.readBlocks(0, 1)
        val buf = ByteBuffer.wrap(mbr).order(ByteOrder.LITTLE_ENDIAN)
        var best = -1L
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val start = buf.getInt(base + 8).toLong() and 0xFFFFFFFFL
            val size = buf.getInt(base + 12).toLong() and 0xFFFFFFFFL
            if (size > 0 && start > 0 && (best < 0 || start < best)) best = start
        }
        return best
    }
}
