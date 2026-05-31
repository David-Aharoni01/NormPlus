package com.norm2hacked.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("watch_prefs")

@Singleton
class WatchPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_MAC = stringPreferencesKey("device_mac")
    private val KEY_LAST_SYNC = longPreferencesKey("last_sync_epoch")
    private val KEY_UNITS = stringPreferencesKey("units")  // "METRIC" | "IMPERIAL"
    private val KEY_DEVICE_VERSION = stringPreferencesKey("device_version")

    suspend fun getDeviceMac(): String? =
        context.dataStore.data.firstOrNull()?.get(KEY_MAC)

    suspend fun saveDeviceMac(mac: String) {
        context.dataStore.edit { it[KEY_MAC] = mac }
    }

    suspend fun clearDeviceMac() {
        context.dataStore.edit { it.remove(KEY_MAC) }
    }

    val lastSyncEpoch: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

    suspend fun saveLastSyncEpoch(epoch: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNC] = epoch }
    }

    val units: Flow<String> = context.dataStore.data.map { it[KEY_UNITS] ?: "METRIC" }

    suspend fun saveUnits(units: String) {
        context.dataStore.edit { it[KEY_UNITS] = units }
    }

    val deviceVersion: Flow<String> = context.dataStore.data.map { it[KEY_DEVICE_VERSION] ?: "" }

    suspend fun saveDeviceVersion(version: String) {
        context.dataStore.edit { it[KEY_DEVICE_VERSION] = version }
    }
}
