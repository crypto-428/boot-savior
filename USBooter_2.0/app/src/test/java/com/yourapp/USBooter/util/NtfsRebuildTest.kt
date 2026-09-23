package com.yourapp.USBooter.util

import com.yourapp.USBooter.util.DriveImages.BIG_PART_SECTORS
import com.yourapp.USBooter.util.DriveImages.PART_START
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last-resort repair: a partition whose NTFS structures are all gone is
 * rebuilt in place, at its own full size, with the same filesystem builder the
 * flashing step uses. These tests pin down the two things that went wrong
 * before: the rebuild must not need extra partition entries, and it must never
 * run unless the user explicitly accepts losing that partition's files.
 */
class NtfsRebuildTest {

    private fun ids(result: JSONObject): List<String> {
        val array = result.getJSONArray("findings")
        return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
    }

    private fun finding(result: JSONObject, id: String): JSONObject {
        val array = result.getJSONArray("findings")
        return (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("id") == id }
    }

    private fun readLe32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun readLe64(b: ByteArray, o: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    // ------------------------------------------------------------- offered only

    @Test
    fun `hopeless ntfs partition is offered an in-place rebuild marked destructive`() {
        val scan = PartitionRepair.scanDevice(DriveImages.hopelessNtfsPartition())
        assertTrue(ids(scan).contains("ntfs-rebuild-1"))
        val f = finding(scan, "ntfs-rebuild-1")
        assertEquals("destructive", f.getString("severity"))
        assertTrue(f.getBoolean("repairable"))
        assertEquals(1, scan.getInt("destructiveCount"))
    }

    @Test
    fun `rebuild is not applied by risky permission alone`() {
        val device = DriveImages.hopelessNtfsPartition()
        val before = device.sector(PART_START).copyOf()
        val table = device.sector(0).copyOf()

        val repair = PartitionRepair.repairDevice(device, allowRisky = true)

        assertFalse(finding(repair, "ntfs-rebuild-1").getBoolean("applied"))
        assertArrayEquals(before, device.sector(PART_START))
        assertArrayEquals(table, device.sector(0))
    }

    // ------------------------------------------------------------------ applied

    @Test
    fun `rebuild writes one full-size ntfs volume and adds no partition entry`() {
        val device = DriveImages.hopelessNtfsPartition()

        val repair = PartitionRepair.repairDevice(
            device, allowRisky = false, allowDataLoss = true, targetFindingId = "ntfs-rebuild-1"
        )
        assertTrue(finding(repair, "ntfs-rebuild-1").getBoolean("applied"))

        // A real NTFS volume, at the partition's own start and full length.
        val boot = device.sector(PART_START)
        assertEquals("NTFS    ", String(boot, 3, 8, Charsets.US_ASCII))
        assertEquals(0x55, boot[510].toInt() and 0xFF)
        assertEquals(0xAA, boot[511].toInt() and 0xFF)
        // Windows records one sector less than the partition: the last sector
        // holds the backup boot sector and is not part of the volume.
        assertTrue(
            "volume size ${readLe64(boot, 40)} should be $BIG_PART_SECTORS or one less",
            readLe64(boot, 40) == BIG_PART_SECTORS || readLe64(boot, 40) == BIG_PART_SECTORS - 1
        )
        assertArrayEquals(boot, device.sector(PART_START + BIG_PART_SECTORS - 1))

        // The table still describes exactly one partition, unmoved and unshrunk.
        val table = device.sector(0)
        assertEquals(PART_START, readLe32(table, 446 + 8))
        assertEquals(BIG_PART_SECTORS, readLe32(table, 446 + 12))
        assertEquals(0x07, table[446 + 4].toInt() and 0xFF)
        for (slot in 1 until 4) {
            val base = 446 + slot * 16
            assertEquals("slot $slot must stay empty", 0L, readLe32(table, base + 12))
            assertEquals("slot $slot must stay empty", 0, table[base + 4].toInt() and 0xFF)
        }
    }

    @Test
    fun `rebuilt partition passes a fresh scan`() {
        val device = DriveImages.hopelessNtfsPartition()
        PartitionRepair.repairDevice(
            device, allowRisky = false, allowDataLoss = true, targetFindingId = "ntfs-rebuild-1"
        )

        val after = ids(PartitionRepair.scanDevice(device))
        assertFalse(after.contains("ntfs-rebuild-1"))
        assertFalse(after.any { it.startsWith("ntfs-boot") || it.startsWith("ntfs-mft") || it.startsWith("fs-") })
    }

    @Test
    fun `a partition too small for ntfs is never offered a rebuild`() {
        // The stock 16000-sector fixture is under the 16 MiB NTFS minimum.
        val device = FakeBlockDevice(20_000)
        device.put(0, DriveImages.mbr())
        val broken = DriveImages.ntfsBoot(totalSectors = 9_999_999L, mftCluster = 9_999_999L, mirrorCluster = 9_999_999L)
        device.put(PART_START, broken)
        device.put(PART_START + DriveImages.PART_SECTORS - 1, broken)

        val repair = PartitionRepair.repairDevice(
            device, allowRisky = false, allowDataLoss = true, targetFindingId = "ntfs-rebuild-1"
        )
        assertFalse(ids(repair).contains("ntfs-rebuild-1"))
    }

    @Test
    fun `data-loss permission without the selected finding never rebuilds`() {
        val device = DriveImages.hopelessNtfsPartition()
        val before = device.sector(PART_START).copyOf()

        val repair = PartitionRepair.repairDevice(device, allowRisky = true, allowDataLoss = true)

        assertFalse(finding(repair, "ntfs-rebuild-1").getBoolean("applied"))
        assertArrayEquals(before, device.sector(PART_START))
    }
}
