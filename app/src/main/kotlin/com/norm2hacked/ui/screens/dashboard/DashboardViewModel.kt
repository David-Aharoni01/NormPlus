package com.norm2hacked.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.ble.BleConnectionState
import com.norm2hacked.ble.BleManager
import com.norm2hacked.data.db.dao.HeartRateDao
import com.norm2hacked.data.db.dao.SleepDao
import com.norm2hacked.data.db.dao.SportDao
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.domain.model.DailyStats
import com.norm2hacked.domain.usecase.SyncHealthDataUseCase
import com.norm2hacked.domain.usecase.SyncProgress
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.BatteryCommand
import com.norm2hacked.protocol.commands.DeviceDisplayCommand
import com.norm2hacked.protocol.commands.DeviceVersionCommand
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
    private val syncUseCase: SyncHealthDataUseCase,
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
                // Source: BluetoothCommandManager.smali getBatteryPower → BatteryPower(callback, 1, 0)
                //   content = intToByteArray(0, 1) = [0x00], contentLen = 1
                // Packet: [6F 08 70 01 00 00 8F]
                val battPkt = bleManager.sendAndAwait(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
                val batt = BatteryCommand.parse(battPkt)
                _state.update { it.copy(batteryPercent = batt.percent, isCharging = batt.charging) }

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
            }.onFailure { e ->
                android.util.Log.e("DashboardVM", "refreshWatchStats failed: ${e.message}", e)
                _state.update { it.copy(error = "Watch stats unavailable: ${e.message}") }
            }
        }
    }

    fun sync() {
        if (_state.value.isSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, error = null) }
            syncUseCase.syncAll().collect { progress ->
                when (progress) {
                    is SyncProgress.Running -> _state.update { it.copy(syncLabel = progress.label) }
                    is SyncProgress.Done -> {
                        _state.update { it.copy(isSyncing = false, syncLabel = "") }
                        // Refresh DB-derived totals; also re-attempt the live watch summary
                        // (DeviceDisplay) for when that command starts returning data.
                        loadCachedData()
                        refreshWatchStats()
                    }
                    is SyncProgress.Error -> _state.update { it.copy(isSyncing = false, error = progress.message) }
                    else -> Unit
                }
            }
        }
    }
}
