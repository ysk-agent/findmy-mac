package com.prelina.findmynet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure auto-refresh policy in [RefreshSettings]:
 * preset list, defaults, stored-value validation, and labels.
 * The Android store (RefreshSettingsStore) is a thin wrapper over
 * these functions, so pinning the policy here covers the behaviour
 * MapActivity depends on.
 */
class RefreshSettingsTest {

    @Test
    fun `preset list is the agreed set in ascending order`() {
        assertEquals(
            listOf(30, 60, 120, 300, 600),
            RefreshSettings.INTERVAL_PRESETS_SECONDS
        )
        assertEquals(
            RefreshSettings.INTERVAL_PRESETS_SECONDS.sorted(),
            RefreshSettings.INTERVAL_PRESETS_SECONDS
        )
    }

    @Test
    fun `defaults are enabled at 120 seconds`() {
        assertTrue(RefreshSettings.DEFAULT_AUTO_REFRESH_ENABLED)
        assertEquals(120, RefreshSettings.DEFAULT_INTERVAL_SECONDS)
        // The default must itself be a valid preset.
        assertTrue(
            RefreshSettings.DEFAULT_INTERVAL_SECONDS in
                RefreshSettings.INTERVAL_PRESETS_SECONDS
        )
    }

    @Test
    fun `every preset survives sanitizing unchanged`() {
        for (preset in RefreshSettings.INTERVAL_PRESETS_SECONDS) {
            assertEquals(preset, RefreshSettings.sanitizeIntervalSeconds(preset))
        }
    }

    @Test
    fun `non-preset stored values fall back to the default`() {
        for (bad in listOf(0, -30, 1, 45, 90, 119, 121, 240, 601, 3600, Int.MAX_VALUE)) {
            assertEquals(
                "value $bad",
                RefreshSettings.DEFAULT_INTERVAL_SECONDS,
                RefreshSettings.sanitizeIntervalSeconds(bad)
            )
        }
    }

    @Test
    fun `labels match the dialog wording`() {
        assertEquals("30 sec", RefreshSettings.intervalLabel(30))
        assertEquals("1 min", RefreshSettings.intervalLabel(60))
        assertEquals("2 min", RefreshSettings.intervalLabel(120))
        assertEquals("5 min", RefreshSettings.intervalLabel(300))
        assertEquals("10 min", RefreshSettings.intervalLabel(600))
    }
}
