package com.normplus.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleConnectionState
import com.normplus.ble.BleManager
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.preferences.WatchPreferences
import com.normplus.domain.model.DailyStats
import com.normplus.status.SyncState
import com.normplus.status.WatchStatusSource
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.commands.DeviceDisplayCommand
import com.normplus.protocol.commands.DeviceVersionCommand
import com.normplus.protocol.commands.SwitchSettingCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DashboardUiState(
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val steps: Int = 0,
    val stepGoal: Int = 10_000,
    val calories: Float = 0f,
    val distanceMeters: Int = 0,
    val activeMinutes: Int = 0,
    val lastHrBpm: Int = 0,
    val lastHrTimestamp: Long = 0L,
    val sleepMinutes: Int = 0,
    val deepSleepMinutes: Int = 0,
    val deviceVersion: String = "",
    val isSyncing: Boolean = false,
    val syncLabel: String = "",
    val lastSyncEpoch: Long = 0L,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val statusSource: WatchStatusSource,
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        observeConnection()
        loadCachedData()
    }

    private fun observeConnection() {
        viewModelScope.launch {
            bleManager.connectionState.collect { cs ->
                _state.update { it.copy(connectionState = cs) }
                if (cs is BleConnectionState.Ready) refreshWatchStats()
            }
        }
        viewModelScope.launch {
            watchPreferences.lastSyncEpoch.collect { epoch ->
                _state.update { it.copy(lastSyncEpoch = epoch) }
            }
        }
        // The battery and the sync are the shared status's (#97): read once for the whole app,
        // the sync run where every screen can see its progress.
        viewModelScope.launch {
            var wasSyncing = false
            statusSource.status.collect { status ->
                val sync = status.sync
                _state.update {
                    it.copy(
                        batteryPercent = status.battery?.percent ?: 0,
                        isCharging = status.battery?.charging ?: false,
                        isSyncing = sync is SyncState.Running,
                        syncLabel = if (sync is SyncState.Running && sync.total > 0) "${sync.done}/${sync.total}" else "",
                    )
                }
                if (wasSyncing && sync is SyncState.Finished) {
                    sync.problems.firstOrNull()?.let { p -> _state.update { it.copy(error = p.detail) } }
                    // Refresh DB-derived totals, and the live watch summary.
                    loadCachedData()
                    refreshWatchStats()
                }
                wasSyncing = sync is SyncState.Running
            }
        }
    }

    private fun loadCachedData() {
        val now = LocalDate.now()
        val startOfDay = now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = startOfDay + 86_400_000L
        viewModelScope.launch {
            val steps = sportDao.sumSteps(startOfDay, endOfDay) ?: 0
            val calories = sportDao.sumCalories(startOfDay, endOfDay) ?: 0f
            val latestHr = heartRateDao.queryLatest()
            val sleep = sleepDao.querySleepBreakdown(startOfDay - 86_400_000L, startOfDay)
            _state.update { s ->
                s.copy(
                    steps = steps,
                    calories = calories,
                    lastHrBpm = latestHr?.bpm ?: 0,
                    lastHrTimestamp = latestHr?.timestampEpoch ?: 0L,
                    sleepMinutes = sleep?.totalMinutes ?: 0,
                    deepSleepMinutes = (sleep?.deepSec ?: 0) / 60,
                )
            }
        }
    }

    private fun refreshWatchStats() {
        viewModelScope.launch {
            runCatching {
                // The battery is read by WatchStatusSource on every connection (#97).

                // Source: MBluetooth.smali getDeviceFunctionInfo → DeviceVersion(callback, 1, 6)
                //   payload = [0x06] (type 6 = full version info string)
                val verPkt = bleManager.sendAndAwait(CommandCode.DEVICE_VERSION, Action.CHECK, byteArrayOf(6))
                val version = DeviceVersionCommand.parseVersionString(verPkt)
                watchPreferences.saveDeviceVersion(version)
                _state.update { it.copy(deviceVersion = version) }

                // The watch-face "today" totals — the live cumulative summary the watch shows on
                // its own screen. This is the correct source for the dashboard (the DB sport
                // records are auto-detected activity snippets, not the all-day pedometer total).
                // Source: DeviceDisplayData.smali (cmd 0x57).
                val displayPkt = bleManager.sendAndAwait(
                    DeviceDisplayCommand.CMD, Action.CHECK, DeviceDisplayCommand.queryPayload()
                )
                // The watch returns real display data as a CHECK_RESPONSE (cmd 0x57); a bare
                // generic SET_RESPONSE ack (payload [0x57, status]) means "no data" — don't let
                // that wipe the cached values.
                if (displayPkt.action == Action.CHECK_RESPONSE && displayPkt.payload.size >= 4) {
                    val display = DeviceDisplayCommand.parse(displayPkt)
                    android.util.Log.i(
                        "DashboardVM",
                        "DeviceDisplay: step=${display.step} cal=${display.calorie} dist=${display.distanceMeters} " +
                            "sleep=${display.sleepMinutes} sportTime=${display.sportTimeMinutes} hr=${display.heartRate} mood=${display.mood}"
                    )
                    _state.update {
                        it.copy(
                            steps = display.step,
                            calories = display.calorie.toFloat(),
                            distanceMeters = display.distanceMeters,
                            activeMinutes = display.sportTimeMinutes,
                            sleepMinutes = display.sleepMinutes,
                            lastHrBpm = if (display.heartRate > 0) display.heartRate else it.lastHrBpm,
                        )
                    }
                } else {
                    android.util.Log.w(
                        "DashboardVM",
                        "DeviceDisplay (0x57) returned no data (action=${displayPkt.action}, ${displayPkt.payload.size}B) — keeping cached totals"
                    )
                }

                // Enable notification kinds by default: the watch's SwitchSetting bitmask gates
                // which notifications it will DISPLAY. With our kinds off, every pushed notification
                // is received and silently dropped. OR them into the current mask (non-destructive,
                // preserving the user's other toggles) once per connection. Best-effort: a failure
                // here must not break the dashboard, so it's isolated in its own runCatching.
                // The first query right after connect can transiently time out, so retry a couple
                // of times. OR only ADDS the low notification bits, preserving every other bit of
                // the watch's mask (including high bytes), so it's safe to apply.
                var current: Int? = null
                repeat(3) {
                    if (current != null) return@repeat
                    current = runCatching {
                        SwitchSettingCommand.parse(
                            bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.CHECK, SwitchSettingCommand.queryPayload())
                        )
                    }.getOrNull()
                }
                current?.let { cur ->
                    val wanted = cur or SwitchSettingCommand.NOTIFICATION_BITS
                    if (wanted != cur) {
                        runCatching {
                            bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.SET, SwitchSettingCommand.setPayload(wanted))
                        }.onSuccess {
                            android.util.Log.i("DashboardVM", "Enabled notification kinds on watch: 0x%08X -> 0x%08X".format(cur, wanted))
                        }.onFailure { android.util.Log.w("DashboardVM", "enable notification kinds SET failed: ${it.message}") }
                    } else {
                        android.util.Log.d("DashboardVM", "Notification kinds already enabled (mask=0x%08X)".format(cur))
                    }
                } ?: android.util.Log.w("DashboardVM", "could not read switch mask to enable notification kinds")
            }.onFailure { e ->
                android.util.Log.e("DashboardVM", "refreshWatchStats failed: ${e.message}", e)
                _state.update { it.copy(error = "Watch stats unavailable: ${e.message}") }
            }
        }
    }

    /** Pull to refresh and the old Sync button: the shared sync (WatchStatusSource, #97). */
    fun sync() {
        _state.update { it.copy(error = null) }
        statusSource.sync()
    }
}
