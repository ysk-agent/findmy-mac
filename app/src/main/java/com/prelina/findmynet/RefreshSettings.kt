package com.prelina.findmynet

import android.content.Context

/**
 * Auto-refresh settings for the map screen.
 *
 * [RefreshSettings] holds the pure policy: the preset interval list,
 * the defaults, validation of stored values, and the English labels.
 * It has no Android dependency, so it is unit-testable with plain JVM
 * tests.
 *
 * [RefreshSettingsStore] is the thin Android side, following the
 * TargetDeviceStore pattern: values live in their own plain
 * SharedPreferences file. An on/off flag and an interval are not
 * credentials, so no encryption is needed here (session/token storage
 * is SessionManager's concern and is not touched).
 */
object RefreshSettings {

    /** Selectable auto-refresh intervals, in seconds. */
    val INTERVAL_PRESETS_SECONDS: List<Int> = listOf(30, 60, 120, 300, 600)

    const val DEFAULT_INTERVAL_SECONDS = 120
    const val DEFAULT_AUTO_REFRESH_ENABLED = true

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
}

class RefreshSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** User preference: whether the foreground auto-refresh timer runs. */
    var autoRefreshEnabled: Boolean
        get() = prefs.getBoolean(
            KEY_AUTO_REFRESH_ENABLED,
            RefreshSettings.DEFAULT_AUTO_REFRESH_ENABLED
        )
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_REFRESH_ENABLED, value).apply()
        }

    /**
     * Auto-refresh interval in seconds. The getter sanitizes, so a
     * stored value outside the preset list reads back as the default.
     */
    var intervalSeconds: Int
        get() = RefreshSettings.sanitizeIntervalSeconds(
            prefs.getInt(KEY_INTERVAL_SECONDS, RefreshSettings.DEFAULT_INTERVAL_SECONDS)
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_INTERVAL_SECONDS, RefreshSettings.sanitizeIntervalSeconds(value))
                .apply()
        }

    companion object {
        private const val PREFS_NAME = "refresh_settings"
        private const val KEY_AUTO_REFRESH_ENABLED = "auto_refresh_enabled"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
    }
}
