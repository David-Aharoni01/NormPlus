package com.normplus.ui.screens.technical

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleService
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.preferences.WatchPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What Technical shows besides the shared status (#107): the phone's own facts about the link. */
@Immutable
data class TechnicalUiState(
    val serviceRunning: Boolean = false,
    val startOnBoot: Boolean = true,
    val address: String? = null,
    val version: String = "",
    val lastConnectedEpochMs: Long = 0L,
    val sportRecords: Int = 0,
    val heartRateRecords: Int = 0,
    val sleepSessions: Int = 0,
)

@HiltViewModel
class TechnicalViewModel @Inject constructor(
    private val prefs: WatchPreferences,
    sport: SportDao,
    heartRate: HeartRateDao,
    sleep: SleepDao,
) : ViewModel() {

    private val facts = combine(
        BleService.isRunning,
        prefs.autoStartEnabled,
        prefs.deviceMac,
        prefs.deviceVersion,
        prefs.lastConnectedEpoch,
    ) { running, boot, mac, version, connected ->
        TechnicalUiState(serviceRunning = running, startOnBoot = boot, address = mac, version = version, lastConnectedEpochMs = connected)
    }

    private val counts = combine(sport.observeCount(), heartRate.observeCount(), sleep.observeCount()) { s, h, z -> Triple(s, h, z) }

    val state: StateFlow<TechnicalUiState> = combine(facts, counts) { f, c ->
        f.copy(sportRecords = c.first, heartRateRecords = c.second, sleepSessions = c.third)
    }
        .catch { emit(TechnicalUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TechnicalUiState())

    fun setStartOnBoot(on: Boolean) {
        viewModelScope.launch { prefs.setAutoStartEnabled(on) }
    }
}
