package com.yourapp.USBooter.util

import com.yourapp.USBooter.util.DriveImages.PART_SECTORS
import com.yourapp.USBooter.util.DriveImages.PART_START
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two promises the repair engine makes: a finding marked "safe" is
 * rebuilt from a copy already on the drive, and a finding marked "risky" is
 * never applied unless the user explicitly allows it.
 */
class PartitionRepairTest {

    private fun ids(result: JSONObject): List<String> {
        val array = result.getJSONArray("findings")
        return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
    }

    private fun finding(result: JSONObject, id: String): JSONObject {
        val array = result.getJSONArray("findings")
        return (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("id") == id }
    }

    // ------------------------------------------------------------------ clean

    @Test
    fun `healthy ntfs drive reports nothing to repair`() {
        val result = PartitionRepair.scanDevice(DriveImages.healthyNtfsDrive())
        assertEquals(listOf("clean"), ids(result))
        assertEquals("info", finding(result, "clean").getString("severity"))
        assertEquals(0, result.getInt("problemCount"))
    }

    @Test
    fun `progress is reported and ends at one hundred`() {
        val steps = mutableListOf<Int>()
        PartitionRepair.repairDevice(DriveImages.healthyNtfsDrive(), allowRisky = false) { p, _ -> steps.add(p) }
        assertTrue(steps.isNotEmpty())
        assertEquals(100, steps.last())
    }

    // ------------------------------------------------------------------- safe

    @Test
    fun `ntfs boot sector is restored from its backup copy without asking`() {
        val device = DriveImages.healthyNtfsDrive()
        // Damage only the main boot sector: the size field no longer matches.
        val broken = DriveImages.ntfsBoot()
        DriveImages.le(broken, 40, 999_999L, 8)
        device.put(PART_START, broken)

        val scan = PartitionRepair.scanDevice(device)
        val f = finding(scan, "ntfs-boot-1")
        assertEquals("safe", f.getString("severity"))
        assertTrue(f.getBoolean("repairable"))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals(1, repair.getInt("appliedCount"))
        assertTrue(finding(repair, "ntfs-boot-1").getBoolean("applied"))
        // The restored sector is now identical to the surviving copy.
        assertArrayEquals(device.sector(PART_START + PART_SECTORS - 1), device.sector(PART_START))
        assertTrue(device.cacheFlushes > 0)
        // And a re-scan finds nothing left to do.
        assertEquals(listOf("clean"), ids(PartitionRepair.scanDevice(device)))
    }

    @Test
    fun `damaged ntfs backup copy is rewritten from the healthy main sector`() {
        val device = DriveImages.healthyNtfsDrive()
        device.put(PART_START + PART_SECTORS - 1, ByteArray(512))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertTrue(ids(repair).contains("ntfs-copy-1"))
        assertEquals("safe", finding(repair, "ntfs-copy-1").getString("severity"))
        assertEquals(1, repair.getInt("appliedCount"))
        assertArrayEquals(device.sector(PART_START), device.sector(PART_START + PART_SECTORS - 1))
    }

    @Test
    fun `wrong mbr type byte is corrected in place`() {
        val device = DriveImages.healthyNtfsDrive()
        device.put(0, DriveImages.mbr(type = 0x0C))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals("safe", finding(repair, "mbr-type-1").getString("severity"))
        assertEquals(0x07, device.sector(0)[446 + 4].toInt() and 0xFF)
    }

    @Test
    fun `missing active flag is set on the first partition`() {
        val device = DriveImages.healthyNtfsDrive()
        device.put(0, DriveImages.mbr(active = false))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals("safe", finding(repair, "mbr-active").getString("severity"))
        assertEquals(0x80, device.sector(0)[446].toInt() and 0xFF)
    }

    @Test
    fun `damaged main gpt is restored from the backup header`() {
        val total = 20_000L
        val device = FakeBlockDevice(total)
        device.put(0, DriveImages.protectiveMbr(total - 1))
        device.put(total - 1, DriveImages.gptHeader(myLba = total - 1, altLba = 1, entryLba = total - 33, totalBlocks = total))
        // LBA 1 stays zeroed: the main table is gone.

        val scan = PartitionRepair.scanDevice(device)
        assertEquals("safe", finding(scan, "gpt-primary").getString("severity"))

        PartitionRepair.repairDevice(device, allowRisky = false)
        val restored = device.sector(1)
        assertEquals("EFI PART", String(restored, 0, 8, Charsets.US_ASCII))
        assertEquals(listOf("clean"), ids(PartitionRepair.scanDevice(device)))
    }

    @Test
    fun `missing gpt backup is rebuilt from the main header`() {
        val total = 20_000L
        val device = FakeBlockDevice(total)
        device.put(0, DriveImages.protectiveMbr(total - 1))
        device.put(1, DriveImages.gptHeader(myLba = 1, altLba = total - 1, entryLba = 2, totalBlocks = total))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals("safe", finding(repair, "gpt-backup").getString("severity"))
        assertEquals("EFI PART", String(device.sector(total - 1), 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `missing protective mbr is written back`() {
        val total = 20_000L
        val device = FakeBlockDevice(total)
        device.put(1, DriveImages.gptHeader(myLba = 1, altLba = total - 1, entryLba = 2, totalBlocks = total))
        device.put(total - 1, DriveImages.gptHeader(myLba = total - 1, altLba = 1, entryLba = total - 33, totalBlocks = total))

        val repair = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals("safe", finding(repair, "gpt-pmbr").getString("severity"))
        assertEquals(0xEE, device.sector(0)[446 + 4].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ risky

    @Test
    fun `risky ntfs rebuild is skipped unless the user allows it`() {
        val device = brokenNtfsBothCopies()
        val before = device.sector(PART_START).copyOf()

        val scan = PartitionRepair.scanDevice(device)
        val f = finding(scan, "ntfs-boot-1")
        assertEquals("risky", f.getString("severity"))
        assertTrue(f.getBoolean("repairable"))

        val refused = PartitionRepair.repairDevice(device, allowRisky = false)
        assertEquals(0, refused.getInt("appliedCount"))
        assertFalse(finding(refused, "ntfs-boot-1").getBoolean("applied"))
        assertArrayEquals(before, device.sector(PART_START))
    }

    @Test
    fun `risky ntfs rebuild recomputes the boot sector when allowed`() {
        val device = brokenNtfsBothCopies()

        val repair = PartitionRepair.repairDevice(device, allowRisky = true)
        assertTrue(finding(repair, "ntfs-boot-1").getBoolean("applied"))

        val rebuilt = device.sector(PART_START)
        assertEquals("NTFS    ", String(rebuilt, 3, 8, Charsets.US_ASCII))
        assertEquals(PART_SECTORS - 1, readLe64(rebuilt, 40))
        assertEquals(0xAA, rebuilt[511].toInt() and 0xFF)
        // The recomputed sector is mirrored into the backup slot too.
        assertArrayEquals(rebuilt, device.sector(PART_START + PART_SECTORS - 1))
        assertEquals(listOf("clean"), ids(PartitionRepair.scanDevice(device)))
    }

    @Test
    fun `unrepairable ntfs is reported without a fix when no file table is left`() {
        val device = FakeBlockDevice(20_000)
        device.put(0, DriveImages.mbr())
        val broken = DriveImages.ntfsBoot(totalSectors = 999_999L)
        device.put(PART_START, broken)
        device.put(PART_START + PART_SECTORS - 1, broken)
        // No MFT record anywhere on the drive.

        val repair = PartitionRepair.repairDevice(device, allowRisky = true)
        val f = finding(repair, "ntfs-boot-1")
        assertEquals("risky", f.getString("severity"))
        assertFalse(f.getBoolean("repairable"))
        assertEquals(0, repair.getInt("appliedCount"))
    }

    @Test
    fun `drive with no table and no filesystem is reported as unrecoverable`() {
        val device = FakeBlockDevice(20_000)

        val repair = PartitionRepair.repairDevice(device, allowRisky = true)
        assertEquals(listOf("mbr-unrecoverable"), ids(repair))
        val f = finding(repair, "mbr-unrecoverable")
        assertEquals("risky", f.getString("severity"))
        assertFalse(f.getBoolean("repairable"))
        assertEquals(0, repair.getInt("appliedCount"))
    }

    @Test
    fun `missing table with a surviving filesystem is a risky rebuild`() {
        val device = FakeBlockDevice(20_000)
        device.put(PART_START, DriveImages.ntfsBoot())
        device.put(PART_START + PART_SECTORS - 1, DriveImages.ntfsBoot())
        device.put(PART_START + 100 * 8, DriveImages.mftRecord())

        val scan = PartitionRepair.scanDevice(device)
        assertEquals("risky", finding(scan, "mbr-missing").getString("severity"))
        assertEquals(0, PartitionRepair.repairDevice(device, allowRisky = false).getInt("appliedCount"))

        val allowed = PartitionRepair.repairDevice(device, allowRisky = true)
        assertTrue(finding(allowed, "mbr-missing").getBoolean("applied"))
        val table = device.sector(0)
        assertEquals(0xAA, table[511].toInt() and 0xFF)
        assertEquals(0x07, table[446 + 4].toInt() and 0xFF)
        assertEquals(PART_START, readLe32(table, 446 + 8))
    }

    // ---------------------------------------------------------------- helpers

    private fun brokenNtfsBothCopies(): FakeBlockDevice {
        val device = FakeBlockDevice(20_000)
        device.put(0, DriveImages.mbr())
        val broken = DriveImages.ntfsBoot(totalSectors = 999_999L)
        device.put(PART_START, broken)
        device.put(PART_START + PART_SECTORS - 1, broken)
        device.put(PART_START + 100 * 8, DriveImages.mftRecord())
        device.put(PART_START + 200 * 8, DriveImages.mftRecord())
        return device
    }

    private fun readLe64(b: ByteArray, o: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun readLe32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
