package com.prelina.findmynet

import android.content.Context

/**
 * Batch 2 - Mac-only narrowing (AUDIT section 6).
 *
 * [DeviceFilter] holds the pure filtering/labelling logic. It depends
 * only on [DeviceData], so it is unit-testable in principle with plain
 * constructed fixtures - no Android, network, or preferences involved.
 *
 * [TargetDeviceStore] is the thin Android side: it persists the chosen
 * device's stable [DeviceData.id] in its own plain SharedPreferences
 * file. Only the device id is stored here - it is not a credential.
 * Session/token storage is SessionManager's concern and is deliberately
 * NOT touched by this batch (its encryption is batch 3).
 */
object DeviceFilter {

    /**
     * Keep-filter for the narrowed view.
     *
     * - No target saved (null/empty): the list is returned unchanged
     *   (first run, before the picker has been answered).
     * - Target saved and present in this fetch: only that device.
     * - Target saved but absent from this fetch (e.g. Apple returned a
     *   partial list, or the id changed): fall back to devices whose
     *   deviceClass is "mac" (case-insensitive), per AUDIT section 6.1.
     */
    fun keepTarget(devices: List<DeviceData>, targetDeviceId: String?): List<DeviceData> {
        if (targetDeviceId.isNullOrEmpty()) {
            return devices
        }
        val byId = devices.filter { it.id == targetDeviceId }
        if (byId.isNotEmpty()) {
            return byId
        }
        return devices.filter { it.deviceClass.equals("mac", ignoreCase = true) }
    }

    /**
     * Family-tab filter: keep only devices shared with the user
     * through Family Sharing (the parser's fmlyShare flag). Order is
     * preserved. Own devices (fmlyShare false) belong to the My Mac
     * side and are dropped here.
     */
    fun familyOnly(devices: List<DeviceData>): List<DeviceData> =
        devices.filter { it.fmlyShare }

    /**
     * One-line picker label: device name plus its class and model, so
     * same-named devices can still be told apart in the picker.
     */
    fun pickerLabel(device: DeviceData): String {
        val model = device.modelDisplayName.ifEmpty {
            device.deviceDisplayName.ifEmpty { device.rawDeviceModel }
        }
        val parts = listOf(device.name, device.deviceClass, model)
            .filter { it.isNotEmpty() }
        return parts.joinToString(" — ")
    }
}

class TargetDeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Stable DeviceData.id of the chosen target device, or null. */
    var targetDeviceId: String?
        get() = prefs.getString(KEY_TARGET_DEVICE_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) {
                    remove(KEY_TARGET_DEVICE_ID)
                } else {
                    putString(KEY_TARGET_DEVICE_ID, value)
                }
            }.apply()
        }

    fun clear() {
        targetDeviceId = null
    }

    companion object {
        private const val PREFS_NAME = "target_device"
        private const val KEY_TARGET_DEVICE_ID = "target_device_id"
    }
}
