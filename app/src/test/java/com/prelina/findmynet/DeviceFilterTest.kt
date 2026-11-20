package com.prelina.findmynet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DeviceFilter.familyOnly], the Family tab's filter:
 * only devices shared through Family Sharing (fmlyShare) are shown
 * there. Fixtures are plain constructed [DeviceData] values — the
 * filter is pure, so no Android or network is involved.
 */
class DeviceFilterTest {

    private fun device(id: String, fmlyShare: Boolean): DeviceData = DeviceData(
        id = id,
        name = "Device $id",
        deviceClass = "iphone",
        deviceModel = "iPhone16,2",
        rawDeviceModel = "iPhone16,2",
        deviceDisplayName = "iPhone",
        modelDisplayName = "iPhone 15 Pro",
        batteryLevel = 80,
        batteryStatus = "NotCharging",
        deviceStatus = 200,
        location = null,
        lowPowerMode = false,
        fmlyShare = fmlyShare,
        thisDevice = false
    )

    @Test
    fun `familyOnly keeps shared devices and drops own devices`() {
        val own = device("own-1", fmlyShare = false)
        val shared = device("family-1", fmlyShare = true)
        val ownMac = device("own-2", fmlyShare = false)
        val sharedWatch = device("family-2", fmlyShare = true)

        val result = DeviceFilter.familyOnly(listOf(own, shared, ownMac, sharedWatch))

        assertEquals(listOf(shared, sharedWatch), result)
    }

    @Test
    fun `familyOnly preserves the input order`() {
        val devices = listOf(
            device("c", fmlyShare = true),
            device("a", fmlyShare = true),
            device("b", fmlyShare = true)
        )

        assertEquals(devices, DeviceFilter.familyOnly(devices))
    }

    @Test
    fun `familyOnly of an empty list is empty`() {
        assertTrue(DeviceFilter.familyOnly(emptyList()).isEmpty())
    }

    @Test
    fun `familyOnly drops everything when nothing is shared`() {
        val devices = listOf(
            device("own-1", fmlyShare = false),
            device("own-2", fmlyShare = false)
        )

        assertTrue(DeviceFilter.familyOnly(devices).isEmpty())
    }
}
