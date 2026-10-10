package com.normplus.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    // ── Always-on / uptime prefs ──────────────────────────────────────────────
    // Whether the connection service may (re)start itself unattended — on boot, after an app
    // update, on a START_STICKY restart, or from the periodic watchdog. Cleared by the
    // notification's "Stop" action so a deliberate stop actually stays stopped, and set
    // again the next time the user opens the app.
    private val KEY_AUTOSTART = booleanPreferencesKey("autostart_enabled")
    // The user dismissed the battery-optimization exemption prompt — never nag again.
    private val KEY_BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_opt_prompt_dismissed")
    private val KEY_DELETE_AFTER_SYNC = booleanPreferencesKey("delete_after_sync")
    // Wall-clock time of the last time the link actually reached Ready. Shown in Connection health.
    private val KEY_LAST_CONNECTED = longPreferencesKey("last_connected_epoch")

    suspend fun getDeviceMac(): String? =
        context.dataStore.data.firstOrNull()?.get(KEY_MAC)

    /** The saved watch's address: null before the first run saves one, and after it is forgotten. */
    val deviceMac: Flow<String?> = context.dataStore.data.map { it[KEY_MAC] }

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

    // ── Always-on / uptime prefs ──────────────────────────────────────────────

    /** Default true: the whole point of the service is to be always-on unless the user says no. */
    val autoStartEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTOSTART] ?: true }

    suspend fun isAutoStartEnabled(): Boolean =
        context.dataStore.data.firstOrNull()?.get(KEY_AUTOSTART) ?: true

    suspend fun setAutoStartEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTOSTART] = enabled }
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    /**
     * Default true (#91): delete each record type from the watch once the sync has all of it, as
     * the official app does, so the next sync reads only what is new -- the watch cannot stream
     * from an index. Off keeps the records on the watch for another app to read too.
     */
    val deleteAfterSync: Flow<Boolean> = context.dataStore.data.map { it[KEY_DELETE_AFTER_SYNC] ?: true }

    suspend fun isDeleteAfterSync(): Boolean =
        context.dataStore.data.firstOrNull()?.get(KEY_DELETE_AFTER_SYNC) ?: true

    suspend fun setDeleteAfterSync(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DELETE_AFTER_SYNC] = enabled }
    }

    val batteryPromptDismissed: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_BATTERY_PROMPT_DISMISSED] ?: false }

    suspend fun setBatteryPromptDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[KEY_BATTERY_PROMPT_DISMISSED] = dismissed }
    }

    val lastConnectedEpoch: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_CONNECTED] ?: 0L }

    suspend fun saveLastConnectedEpoch(epoch: Long) {
        context.dataStore.edit { it[KEY_LAST_CONNECTED] = epoch }
    }

    // ── Today (#99) ───────────────────────────────────────────────────────────

    private val KEY_STEP_GOAL = intPreferencesKey("step_goal")
    private val KEY_TODAY_SUMMARY = stringPreferencesKey("today_summary")

    /**
     * The step goal Norm+ keeps (brief §7.2): Today and History measure against it, and the
     * Watch tab sets it and sends it to the watch on connect (#102). Default 10,000, as
     * WatchSettings.stepGoal (#100's key and default; the official app's own is 7,000,
     * SPDefaultPrivateValue.DEFAULT_GOAL_STEP = 0x1b58).
     */
    val stepGoal: Flow<Int> = context.dataStore.data.map { it[KEY_STEP_GOAL] ?: 10_000 }

    suspend fun saveStepGoal(steps: Int) {
        context.dataStore.edit { it[KEY_STEP_GOAL] = steps }
    }

    /**
     * The last reading of the watch's own today summary (0x57), encoded by
     * `TodaySummary.encode()`, so Today shows it "as of" its time while the watch is away.
     */
    val todaySummary: Flow<String?> = context.dataStore.data.map { it[KEY_TODAY_SUMMARY] }

    suspend fun saveTodaySummary(encoded: String) {
        context.dataStore.edit { it[KEY_TODAY_SUMMARY] = encoded }
    }

}
