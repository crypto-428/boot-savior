package com.yourapp.USBooter.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Inspects an ISO before anything is written: is it isohybrid (raw-cloneable),
 * does its El Torito boot catalog advertise UEFI and/or BIOS boot, does the file
 * tree actually contain an EFI bootloader, and is any file too big for FAT32.
 */
object IsoAnalyzer {

    private val EFI_BOOT_FILES = listOf(
        "EFI/BOOT/BOOTX64.EFI",
        "EFI/BOOT/BOOTIA32.EFI",
        "EFI/BOOT/BOOTAA64.EFI"
    )

    fun analyze(source: IsoSource): IsoInfo {
        val hybrid = isIsoHybrid(source)
        val (bios, efi) = readElToritoPlatforms(source)

        val directory = ImageDirectory.read(source)
        val entries = directory.entries

        val normalized = entries.filter { !it.isDirectory }.associateBy { it.path.trim('/').uppercase() }
        val hasEfiFile = EFI_BOOT_FILES.any { normalized.containsKey(it) }
        val isPuppy = entries.any { entry ->
            !entry.isDirectory && entry.path.substringAfterLast('/').lowercase().let { name ->
                name.startsWith("puppy_") && name.endsWith(".sfs")
            }
        }
        val largest = directory.largestFileBytes

        val lowerPaths = entries.filter { !it.isDirectory }.map { it.path.trimStart('/').lowercase() }
        val lowerSet = lowerPaths.toHashSet()
        val hasBootmgr = lowerSet.contains("bootmgr") || lowerSet.contains("bootmgr.efi")
        val hasBootWim = lowerSet.contains("sources/boot.wim")
        // Windows setup only cares about sources/install.wim|.esd - its size is
        // what decides FAT32 vs. a FAT32 + exFAT split.
        // Windows ships the payload as install.wim, install.esd or a split
        // install.swm set; whichever is present decides FAT32 vs. FAT32+exFAT.
        val installCandidates = listOf(
            "sources/install.wim", "sources/install.esd",
            "sources/install.swm", "sources/install2.swm"
        ).mapNotNull { directory.find(it) }
        val installImage = installCandidates.maxByOrNull { it.size }
        // A real installer needs an install payload; bootmgr + boot.wim alone is
        // WinPE / a recovery image, which must not be pushed through the split logic.
        val isWindows = hasBootmgr && hasBootWim && installImage != null
        val hasDistroGrub = listOf("boot/grub/grub.cfg", "boot/grub2/grub.cfg", "efi/boot/grub.cfg")
            .any { lowerSet.contains(it) }
        val hasIsolinux = listOf(
            "isolinux/isolinux.cfg", "boot/isolinux/isolinux.cfg",
            "syslinux/syslinux.cfg", "boot/syslinux/syslinux.cfg"
        ).any { lowerSet.contains(it) }
        val persistenceFamily = when {
            lowerPaths.any { it.startsWith("casper/") } -> "casper"
            lowerPaths.any { it.startsWith("live/") } -> "live"
            else -> ""
        }
        val kernelPrefixes = listOf("vmlinuz", "vmlinux", "bzimage")
        val hasKernel = lowerPaths.any { path ->
            val base = path.substringAfterLast('/')
            kernelPrefixes.any { base.startsWith(it) } && !base.endsWith(".efi")
        }

        val volumeLabel = directory.volumeLabel.ifBlank { source.displayName.substringBeforeLast('.') }
        val profile = ImageProfile.detect(
            lowerPaths = lowerPaths,
            volumeLabel = volumeLabel,
            displayName = source.displayName,
            isWindowsInstaller = isWindows,
            hasBootWim = hasBootWim,
            isHybrid = hybrid,
            supportsPersistence = persistenceFamily.isNotEmpty()
        )

        return IsoInfo(
            displayName = source.displayName,
            sizeBytes = source.size,
            volumeLabel = volumeLabel,
            isHybrid = hybrid,
            hasEfiBoot = efi,
            hasBiosBoot = bios,
            hasEfiBootFile = hasEfiFile,
            isPuppyLinux = isPuppy,
            largestFileBytes = largest,
            isWindows = isWindows,
            hasDistroGrubConfig = hasDistroGrub,
            hasIsolinuxConfig = hasIsolinux,
            hasKernel = hasKernel,
            directoryFormat = directory.format,
            fileCount = directory.files.size,
            payloadBytes = directory.totalBytes,
            installImagePath = installImage?.path ?: "",
            installImageBytes = installImage?.size ?: 0L,
            persistenceFamily = persistenceFamily,
            hasBootmgr = hasBootmgr,
            profileId = profile.id,
            profileName = profile.name,
            profileCategory = profile.category.displayName,
            profileAdvice = profile.advice,
            profileMode = profile.recommendedMode
        )
    }



    /**
     * An isohybrid image starts with a real MBR: 0x55AA signature plus at least
     * one entry that claims sectors. Some xorriso/syslinux images intentionally
     * use type 0x00 for the ISO-spanning entry, so size—not type—is authoritative.
     */
    private fun isIsoHybrid(source: IsoSource): Boolean {
        val sector = source.readAt(0, 512)
        if ((sector[510].toInt() and 0xFF) != 0x55 || (sector[511].toInt() and 0xFF) != 0xAA) return false
        for (i in 0 until 4) {
            val base = 446 + i * 16
            val sizeLba = ByteBuffer.wrap(sector, base + 12, 4).order(ByteOrder.LITTLE_ENDIAN)
                .int.toLong() and 0xFFFFFFFFL
            if (sizeLba != 0L) return true
        }
        return false
    }

    /** Returns (biosBootAvailable, efiBootAvailable) from the El Torito boot catalog. */
    private fun readElToritoPlatforms(source: IsoSource): Pair<Boolean, Boolean> {
        var catalogLba = -1L
        for (sector in 16 until 32) {
            val vd = source.readAt(sector.toLong() * Iso9660Reader.SECTOR_SIZE, Iso9660Reader.SECTOR_SIZE)
            if (String(vd, 1, 5, Charsets.US_ASCII) != "CD001") break
            val type = vd[0].toInt() and 0xFF
            if (type == 0 && String(vd, 7, 23, Charsets.US_ASCII).startsWith("EL TORITO SPECIFICATION")) {
                catalogLba = (ByteBuffer.wrap(vd, 71, 4).order(ByteOrder.LITTLE_ENDIAN).int).toLong() and 0xFFFFFFFFL
                break
            }
            if (type == 255) break
        }
        if (catalogLba < 0) return Pair(false, false)

        val catalog = source.readAt(catalogLba * Iso9660Reader.SECTOR_SIZE, Iso9660Reader.SECTOR_SIZE)
        var bios = false
        var efi = false
        var offset = 0
        while (offset + 32 <= catalog.size) {
            val header = catalog[offset].toInt() and 0xFF
            when (header) {
                0x01 -> { // validation entry: platform id of the default section
                    if ((catalog[offset + 1].toInt() and 0xFF) == 0xEF) efi = true else bios = true
                }
                0x90, 0x91 -> { // section header: platform id at +1
                    if ((catalog[offset + 1].toInt() and 0xFF) == 0xEF) efi = true else bios = true
                }
                0x00 -> if (offset > 0) return Pair(bios, efi)
            }
            offset += 32
        }
        return Pair(bios, efi)
    }
}
