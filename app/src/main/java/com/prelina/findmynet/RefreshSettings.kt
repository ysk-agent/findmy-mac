package com.prelina.findmynet

import android.content.Context

/**
 * The two device tabs of the map screen. Auto-refresh settings are
 * kept per tab (v0.1.3): each tab has its own enabled flag and
 * interval, and the map screen's timer always follows the active
 * tab's settings.
 */
enum class RefreshTab { MY_MAC, FAMILY }

/**
 * Auto-refresh settings for the map screen.
 *
 * [RefreshSettings] holds the pure policy: the preset interval list,
 * the defaults, validation of stored values, the per-tab preference
 * key scheme, migration resolution, and the English labels. It has no
 * Android dependency, so it is unit-testable with plain JVM tests.
 *
 * [RefreshSettingsStore] is the thin Android side, following the
 * TargetDeviceStore pattern: values live in their own plain
 * SharedPreferences file. An on/off flag and an interval are not
 * credentials, so no encryption is needed here (session/token storage
 * is SessionManager's concern and is not touched).
 *
 * Migration from v0.1.2: that release stored a single settings pair
 * under [LEGACY_KEY_AUTO_REFRESH_ENABLED] / [LEGACY_KEY_INTERVAL_SECONDS].
 * Those keys described what is now the My Mac tab, so they are read
 * as that tab's fallback when its per-tab keys are absent; the
 * Family tab starts from the defaults. A v0.1.2 user's setting is
 * therefore never silently reset.
 */
object RefreshSettings {

    /** Selectable auto-refresh intervals, in seconds. */
    val INTERVAL_PRESETS_SECONDS: List<Int> = listOf(30, 60, 120, 300, 600)

    const val DEFAULT_INTERVAL_SECONDS = 120
    const val DEFAULT_AUTO_REFRESH_ENABLED = true

    /** v0.1.2 single-pair keys; My Mac migration fallback only. */
    const val LEGACY_KEY_AUTO_REFRESH_ENABLED = "auto_refresh_enabled"
    const val LEGACY_KEY_INTERVAL_SECONDS = "interval_seconds"

    /**
     * A stored interval is honoured only when it is one of the
     * presets; anything else (older build, hand-edited prefs) falls
     * back to the default rather than being scheduled as-is.
     */
    fun sanitizeIntervalSeconds(value: Int): Int =
        if (value in INTERVAL_PRESETS_SECONDS) value else DEFAULT_INTERVAL_SECONDS

    /** Short English label for an interval, e.g. "2 min". */
    fun intervalLabel(seconds: Int): String = when (seconds) {
        30 -> "30 sec"
        60 -> "1 min"
        120 -> "2 min"
        300 -> "5 min"
        600 -> "10 min"
        else -> "$seconds sec"
    }

    /**
     * Per-tab preference keys. The two tabs' keys are all distinct,
     * which is what makes the settings independent: writing one tab's
     * value can never touch the other tab's.
     */
    fun autoRefreshEnabledKey(tab: RefreshTab): String = when (tab) {
        RefreshTab.MY_MAC -> "auto_refresh_enabled_my_mac"
        RefreshTab.FAMILY -> "auto_refresh_enabled_family"
    }

    fun intervalSecondsKey(tab: RefreshTab): String = when (tab) {
        RefreshTab.MY_MAC -> "interval_seconds_my_mac"
        RefreshTab.FAMILY -> "interval_seconds_family"
    }

    /**
     * The v0.1.2 keys are a migration fallback for the My Mac tab
     * only; the Family tab has no legacy value to inherit.
     */
    fun legacyAutoRefreshEnabledKey(tab: RefreshTab): String? =
        if (tab == RefreshTab.MY_MAC) LEGACY_KEY_AUTO_REFRESH_ENABLED else null

    fun legacyIntervalSecondsKey(tab: RefreshTab): String? =
        if (tab == RefreshTab.MY_MAC) LEGACY_KEY_INTERVAL_SECONDS else null

    /**
     * Effective enabled flag for a tab: the per-tab value when one is
     * stored, else the legacy (v0.1.2) value, else the default.
     * Either stored argument is null when its key is absent.
     */
    fun resolveAutoRefreshEnabled(perTab: Boolean?, legacy: Boolean?): Boolean =
        perTab ?: legacy ?: DEFAULT_AUTO_REFRESH_ENABLED

    /**
     * Effective interval for a tab, resolved like
     * [resolveAutoRefreshEnabled] and always passed through
     * [sanitizeIntervalSeconds].
     */
    fun resolveIntervalSeconds(perTab: Int?, legacy: Int?): Int =
        sanitizeIntervalSeconds(perTab ?: legacy ?: DEFAULT_INTERVAL_SECONDS)
}

class RefreshSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** User preference for [tab]: whether its auto-refresh timer runs. */
    fun autoRefreshEnabled(tab: RefreshTab): Boolean =
        RefreshSettings.resolveAutoRefreshEnabled(
            storedBoolean(RefreshSettings.autoRefreshEnabledKey(tab)),
            RefreshSettings.legacyAutoRefreshEnabledKey(tab)?.let { storedBoolean(it) }
        )

    fun setAutoRefreshEnabled(tab: RefreshTab, value: Boolean) {
        prefs.edit()
            .putBoolean(RefreshSettings.autoRefreshEnabledKey(tab), value)
            .apply()
    }

    /**
     * Auto-refresh interval in seconds for [tab]. Resolution goes
     * through [RefreshSettings.resolveIntervalSeconds], so a stored
     * value outside the preset list reads back as the default.
     */
    fun intervalSeconds(tab: RefreshTab): Int =
        RefreshSettings.resolveIntervalSeconds(
            storedInt(RefreshSettings.intervalSecondsKey(tab)),
            RefreshSettings.legacyIntervalSecondsKey(tab)?.let { storedInt(it) }
        )

    fun setIntervalSeconds(tab: RefreshTab, value: Int) {
        prefs.edit()
            .putInt(
                RefreshSettings.intervalSecondsKey(tab),
                RefreshSettings.sanitizeIntervalSeconds(value)
            )
            .apply()
    }

    private fun storedBoolean(key: String): Boolean? =
        if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    private fun storedInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    companion object {
        private const val PREFS_NAME = "refresh_settings"
    }
}
