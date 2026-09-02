package com.andrower.markerdeck

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val SETTINGS_DATASTORE_NAME = "markerdeck_settings"

val Context.markerdeckDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME
)

private object SettingsKeys {
    val serviceAddress = stringPreferencesKey("service_address")
    val deviceName = stringPreferencesKey("device_name")
    // Migration-only key from the removed mode setting.
    val legacyMode = stringPreferencesKey("mode")
}

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    // DataStore ignores unknown keys, so the old mode value is harmless on upgrade.
    // It is intentionally neither read nor written by the single-mode settings model.
    val settings: Flow<MarkerDeckSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            MarkerDeckSettings(
                serviceAddress = preferences[SettingsKeys.serviceAddress].orEmpty(),
                deviceName = normalizeDeviceName(preferences[SettingsKeys.deviceName].orEmpty())
            )
        }

    suspend fun save(value: MarkerDeckSettings) {
        dataStore.edit { preferences ->
            preferences[SettingsKeys.serviceAddress] = value.serviceAddress
            preferences[SettingsKeys.deviceName] = normalizeDeviceName(value.deviceName)
            preferences.remove(SettingsKeys.legacyMode)
        }
    }
}
