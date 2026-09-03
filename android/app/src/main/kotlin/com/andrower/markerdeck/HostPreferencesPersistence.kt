package com.andrower.markerdeck

import android.content.Context
import org.json.JSONArray

private const val HOST_PREFERENCES = "markerdeck_host"
private const val HOST_NAME_KEY = "host_name"
private const val DEVICE_RETENTION_KEY = "device_retention_ms"
private const val PRESETS_KEY = "presets"

class AndroidHostStatePersistence(context: Context) : HostStatePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        HOST_PREFERENCES,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun loadSettings(): HostSettings = normalizeHostSettings(
        HostSettings(
            hostName = preferences.getString(HOST_NAME_KEY, "MarkerDeck").orEmpty(),
            deviceRetentionMs = preferences.getLong(
                DEVICE_RETENTION_KEY,
                MARKERDECK_HOST_DEFAULT_RETENTION_MS
            )
        )
    )

    @Synchronized
    override fun saveSettings(settings: HostSettings) {
        val normalized = normalizeHostSettings(settings)
        preferences.edit()
            .putString(HOST_NAME_KEY, normalized.hostName)
            .putLong(DEVICE_RETENTION_KEY, normalized.deviceRetentionMs)
            .apply()
    }

    @Synchronized
    override fun loadPresets(): List<HostPreset>? {
        val raw = preferences.getString(PRESETS_KEY, null) ?: return null
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val preset = array.optJSONObject(index) ?: continue
                    hostPresetFromJson(preset, "saved-${index + 1}")?.let(::add)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun savePresets(presets: List<HostPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(hostPresetToJson(it)) }
        preferences.edit().putString(PRESETS_KEY, array.toString()).apply()
    }
}
