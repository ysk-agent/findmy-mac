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

    // --- Per-tab settings (v0.1.3) ---

    @Test
    fun `both tabs share the same defaults`() {
        // The defaults are tab-independent constants; pinning them
        // here covers the "both tabs default to enabled at 120 s"
        // rule that RefreshSettingsStore relies on for fresh keys.
        for (tab in RefreshTab.values()) {
            assertEquals(
                "tab $tab",
                RefreshSettings.DEFAULT_AUTO_REFRESH_ENABLED,
                RefreshSettings.resolveAutoRefreshEnabled(null, null)
            )
            assertEquals(
                "tab $tab",
                RefreshSettings.DEFAULT_INTERVAL_SECONDS,
                RefreshSettings.resolveIntervalSeconds(null, null)
            )
            // The Family tab has no legacy value to inherit.
            if (tab == RefreshTab.FAMILY) {
                assertEquals(null, RefreshSettings.legacyAutoRefreshEnabledKey(tab))
                assertEquals(null, RefreshSettings.legacyIntervalSecondsKey(tab))
            }
        }
    }

    @Test
    fun `per-tab keys are all distinct and differ from the legacy keys`() {
        // Independence of the two tabs' settings rests on the store
        // writing each tab under its own keys: assert no collisions
        // among the four per-tab keys or with the v0.1.2 keys.
        val keys = listOf(
            RefreshSettings.autoRefreshEnabledKey(RefreshTab.MY_MAC),
            RefreshSettings.intervalSecondsKey(RefreshTab.MY_MAC),
            RefreshSettings.autoRefreshEnabledKey(RefreshTab.FAMILY),
            RefreshSettings.intervalSecondsKey(RefreshTab.FAMILY),
            RefreshSettings.LEGACY_KEY_AUTO_REFRESH_ENABLED,
            RefreshSettings.LEGACY_KEY_INTERVAL_SECONDS
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `legacy fallback exists only for the My Mac tab`() {
        assertEquals(
            RefreshSettings.LEGACY_KEY_AUTO_REFRESH_ENABLED,
            RefreshSettings.legacyAutoRefreshEnabledKey(RefreshTab.MY_MAC)
        )
        assertEquals(
            RefreshSettings.LEGACY_KEY_INTERVAL_SECONDS,
            RefreshSettings.legacyIntervalSecondsKey(RefreshTab.MY_MAC)
        )
        assertEquals(null, RefreshSettings.legacyAutoRefreshEnabledKey(RefreshTab.FAMILY))
        assertEquals(null, RefreshSettings.legacyIntervalSecondsKey(RefreshTab.FAMILY))
    }

    @Test
    fun `stored per-tab value wins over the legacy value`() {
        assertEquals(
            false,
            RefreshSettings.resolveAutoRefreshEnabled(perTab = false, legacy = true)
        )
        assertEquals(
            true,
            RefreshSettings.resolveAutoRefreshEnabled(perTab = true, legacy = false)
        )
        assertEquals(
            300,
            RefreshSettings.resolveIntervalSeconds(perTab = 300, legacy = 60)
        )
        assertEquals(
            30,
            RefreshSettings.resolveIntervalSeconds(perTab = 30, legacy = 600)
        )
    }

    @Test
    fun `legacy value is used when no per-tab value is stored`() {
        // The v0.1.2 -> v0.1.3 migration path for the My Mac tab:
        // a user who changed the single v0.1.2 setting keeps it.
        assertEquals(
            false,
            RefreshSettings.resolveAutoRefreshEnabled(perTab = null, legacy = false)
        )
        assertEquals(
            600,
            RefreshSettings.resolveIntervalSeconds(perTab = null, legacy = 600)
        )
    }

    @Test
    fun `resolved intervals are sanitized whichever source they came from`() {
        assertEquals(
            RefreshSettings.DEFAULT_INTERVAL_SECONDS,
            RefreshSettings.resolveIntervalSeconds(perTab = 45, legacy = 300)
        )
        assertEquals(
            RefreshSettings.DEFAULT_INTERVAL_SECONDS,
            RefreshSettings.resolveIntervalSeconds(perTab = null, legacy = 45)
        )
    }
}
