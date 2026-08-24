package com.yourapp.USBooter.util

import java.io.Serializable

/**
 * Recognises *what kind of image* the user picked, beyond "is it Windows".
 *
 * The point is not cosmetic: a Windows PE / recovery image (Hiren's BootCD PE,
 * WinRE, MSDaRT, Macrium/Acronis rescue) has bootmgr and sources/boot.wim but no
 * sources/install.wim, so the Windows verifier must not demand an install
 * payload; partition/file-recovery suites (Clonezilla, GParted Live, SystemRescue,
 * Rescuezilla, Parted Magic, Redo Rescue, Kaspersky/ESET rescue, DBAN, MemTest86)
 * are plain isolinux/GRUB live systems that boot best in Universal mode; and a few
 * images (MemTest86 USB, Ventoy) only ever boot as a raw clone.
 *
 * Each profile carries the mode USBooter recommends and a one-line explanation
 * the UI shows next to the write-mode cards.
 */
object ImageProfile {

    enum class Category(val displayName: String) : Serializable {
        WINDOWS_INSTALLER("Windows installer"),
        WINDOWS_PE("Windows PE / recovery"),
        LINUX_LIVE("Linux live system"),
        RECOVERY_SUITE("Rescue & recovery suite"),
        FIRMWARE_TOOL("Firmware / hardware tool"),
        UNKNOWN("Generic image")
    }

    data class Profile(
        val id: String,
        val name: String,
        val category: Category,
        val recommendedMode: BootMode,
        /** One line telling the user why this mode suits this image. */
        val advice: String
    ) : Serializable

    private data class Rule(
        val id: String,
        val name: String,
        val category: Category,
        val mode: BootMode,
        val advice: String,
        /** All of these lower-case paths (or path prefixes ending in '/') must exist. */
        val requires: List<String> = emptyList(),
        /** Any one of these substrings in a file path or in the volume label matches. */
        val anyOf: List<String> = emptyList()
    )

    private val RULES = listOf(
        Rule(
            "hirens", "Hiren's BootCD PE", Category.WINDOWS_PE, BootMode.UNIVERSAL,
            "A WinPE rescue desktop: one FAT32 partition with bootmgr and boot.wim. Universal mode boots it on both BIOS and UEFI.",
            anyOf = listOf("hbcd", "hirens", "hbcd_pe")
        ),
        Rule(
            "medicat", "MediCat / Bootable toolkit", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "A multi-tool collection. Universal mode keeps every file readable and leaves the rest of the stick usable.",
            anyOf = listOf("medicat", "ventoy/ventoy.json")
        ),
        Rule(
            "winre", "Windows recovery / PE image", Category.WINDOWS_PE, BootMode.UNIVERSAL,
            "WinPE media: it has boot.wim but no install.wim, so nothing needs splitting. One FAT32 partition boots on BIOS and UEFI.",
            requires = listOf("bootmgr", "sources/boot.wim")
        ),
        Rule(
            "clonezilla", "Clonezilla Live", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Disk-imaging live system (Debian live). Universal mode gives BIOS + UEFI boot and leaves room for a data partition to store images.",
            anyOf = listOf("live/filesystem.squashfs|clonezilla", "clonezilla")
        ),
        Rule(
            "gparted", "GParted Live", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Partition editor live system. Universal mode boots it everywhere; persistence is not needed.",
            anyOf = listOf("gparted")
        ),
        Rule(
            "systemrescue", "SystemRescue", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Arch-based rescue system with TestDisk/PhotoRec. Universal mode boots it on BIOS and UEFI.",
            anyOf = listOf("sysresccd", "systemrescue")
        ),
        Rule(
            "rescuezilla", "Rescuezilla", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Ubuntu-based backup and recovery system; persistence is supported through casper.",
            anyOf = listOf("rescuezilla")
        ),
        Rule(
            "partedmagic", "Parted Magic", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Partitioning, secure-erase and data recovery suite. Universal mode boots it on both firmwares.",
            anyOf = listOf("pmagic")
        ),
        Rule(
            "redo", "Redo Rescue", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Backup/restore live system (Debian live). Universal mode is the right choice.",
            anyOf = listOf("redorescue", "redobackup")
        ),
        Rule(
            "kaspersky", "Antivirus rescue disk", Category.RECOVERY_SUITE, BootMode.UNIVERSAL,
            "Offline antivirus scanner. Universal mode boots it and the update database stays writable.",
            anyOf = listOf("kaspersky", "eset", "drweb", "avira")
        ),
        Rule(
            "memtest", "MemTest86 / memory tester", Category.FIRMWARE_TOOL, BootMode.UNIVERSAL,
            "Memory tester. It ships an EFI loader plus a BIOS image, so Universal mode boots it on both.",
            anyOf = listOf("memtest86", "memtest86+", "mt86")
        ),
        Rule(
            "dban", "DBAN / disk eraser", Category.FIRMWARE_TOOL, BootMode.UNIVERSAL,
            "Legacy BIOS-only eraser: Universal mode installs GRUB 2 so it starts on old machines.",
            anyOf = listOf("dban")
        ),
        Rule(
            "supergrub", "Super Grub2 Disk", Category.FIRMWARE_TOOL, BootMode.UNIVERSAL,
            "Boot-repair helper. Universal mode installs the same GRUB 2 it expects.",
            anyOf = listOf("super_grub", "supergrub")
        ),
        Rule(
            "macrium", "Macrium / Acronis rescue (WinPE)", Category.WINDOWS_PE, BootMode.UNIVERSAL,
            "A WinPE-based backup rescue image: FAT32 plus bootmgr is all it needs.",
            anyOf = listOf("macrium", "acronis", "reflect")
        ),
        Rule(
            "winserver", "Windows Server installer", Category.WINDOWS_INSTALLER, BootMode.WINDOWS,
            "Server installation media behaves exactly like Windows 10/11 media: FAT32 with a split install.wim.",
            anyOf = listOf("sources/install.wim|server")
        )
    )

    /**
     * @param lowerPaths every file path in the image, lower-case, without the
     *        leading slash. [volumeLabel] and [displayName] are matched too,
     *        because several suites only identify themselves there.
     */
    fun detect(
        lowerPaths: Collection<String>,
        volumeLabel: String,
        displayName: String,
        isWindowsInstaller: Boolean,
        hasBootWim: Boolean,
        isHybrid: Boolean,
        supportsPersistence: Boolean
    ): Profile {
        val haystack = (lowerPaths.asSequence() + sequenceOf(volumeLabel.lowercase(), displayName.lowercase()))
            .toList()
        val pathSet = lowerPaths.toHashSet()

        RULES.forEach { rule ->
            val requiresOk = rule.requires.isEmpty() || rule.requires.all { need ->
                if (need.endsWith("/")) haystack.any { it.startsWith(need) } else pathSet.contains(need)
            }
            val anyOk = rule.anyOf.isEmpty() || rule.anyOf.any { token ->
                val parts = token.split("|")
                parts.all { needle -> haystack.any { it.contains(needle) } }
            }
            val matched = when {
                rule.requires.isNotEmpty() && rule.anyOf.isNotEmpty() -> requiresOk && anyOk
                rule.requires.isNotEmpty() -> requiresOk
                else -> anyOk
            }
            // The generic WinPE rule must not swallow a real installer.
            if (matched && !(rule.id == "winre" && isWindowsInstaller)) {
                return rule.copy(
                    mode = if (rule.id == "winserver" && !isWindowsInstaller) BootMode.UNIVERSAL else rule.mode
                ).toProfile()
            }
        }

        return when {
            isWindowsInstaller -> Profile(
                "windows", "Windows 10 / 11 installer", Category.WINDOWS_INSTALLER, BootMode.WINDOWS,
                "Windows installation media: one FAT32 partition, and an install.wim over 4 GB is written as spanned install.swm parts."
            )
            hasBootWim -> Profile(
                "winpe", "Windows PE image", Category.WINDOWS_PE, BootMode.UNIVERSAL,
                "WinPE media without an install payload: a single FAT32 partition boots it on BIOS and UEFI."
            )
            supportsPersistence -> Profile(
                "linuxlive", "Linux live image", Category.LINUX_LIVE, BootMode.UNIVERSAL,
                "A live Linux image: Universal mode boots it on BIOS and UEFI and can add a persistence volume."
            )
            isHybrid -> Profile(
                "hybrid", "Hybrid ISO", Category.UNKNOWN, BootMode.UNIVERSAL,
                "Universal mode is still the safest choice; try raw image clone only if the image refuses to start."
            )
            else -> Profile(
                "generic", "Generic image", Category.UNKNOWN, BootMode.AUTO,
                "USBooter picks the write mode from what the image contains."
            )
        }
    }

    private fun Rule.toProfile() = Profile(id, name, category, mode, advice)
}
