package com.norm2hacked.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.ble.BleManager
import com.norm2hacked.domain.model.AppPage
import com.norm2hacked.domain.model.DndSettings
import com.norm2hacked.domain.model.WatchSettings
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.commands.AppSettingCommand
import com.norm2hacked.protocol.commands.BrightnessCommand
import com.norm2hacked.protocol.commands.DndState
import com.norm2hacked.protocol.commands.DoNotDisturbCommand
import com.norm2hacked.protocol.commands.ControlDeviceCommand
import com.norm2hacked.protocol.commands.GoalCommand
import com.norm2hacked.protocol.commands.ScreenTimeoutCommand
import com.norm2hacked.protocol.commands.SwitchSettingCommand
import com.norm2hacked.protocol.commands.UnitCommand
import com.norm2hacked.protocol.commands.VibrationCommand
import com.norm2hacked.protocol.commands.WorkModeCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoading: Boolean = false,
    val settings: WatchSettings = WatchSettings(),
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class WatchSettingsViewModel @Inject constructor(
    private val bleManager: BleManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // Snapshot of what the watch currently has — we diff against this on save.
    private var originalSettings = WatchSettings()

    // Cancel any in-flight load if the user re-opens settings.
    private var loadJob: Job? = null

    fun loadFromWatch() {
        if (!bleManager.connectionState.value.isConnected) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                // Load each setting sequentially using typed cmd+payload — no unsafe List<Any> casts.
                // sendAndAwait guarantees the response matches both the command code AND
                // the action byte (CHECK_RESPONSE/SET_RESPONSE), so unsolicited watch pushes
                // with the same cmdCode cannot match.
                val brightness = BrightnessCommand.parse(
                    bleManager.sendAndAwait(BrightnessCommand.CMD, Action.CHECK, BrightnessCommand.queryPayload())
                )
                val timeout = ScreenTimeoutCommand.parse(
                    bleManager.sendAndAwait(ScreenTimeoutCommand.CMD, Action.CHECK, ScreenTimeoutCommand.queryPayload())
                )
                val dndRaw = DoNotDisturbCommand.parse(
                    bleManager.sendAndAwait(DoNotDisturbCommand.CMD, Action.CHECK, DoNotDisturbCommand.queryPayload())
                )
                val vibration = VibrationCommand.parse(
                    bleManager.sendAndAwait(VibrationCommand.CMD, Action.CHECK, VibrationCommand.queryPayload())
                )
                val metric = UnitCommand.parse(
                    bleManager.sendAndAwait(UnitCommand.CMD, Action.CHECK, UnitCommand.queryPayload())
                )
                val powerSave = WorkModeCommand.parse(
                    bleManager.sendAndAwait(WorkModeCommand.CMD, Action.CHECK, WorkModeCommand.queryPayload())
                )
                val switchMask = SwitchSettingCommand.parse(
                    bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.CHECK, SwitchSettingCommand.queryPayload())
                )
                val pageOrder = AppSettingCommand.parse(
                    bleManager.sendAndAwait(AppSettingCommand.CMD, Action.CHECK, AppSettingCommand.queryPayload())
                ).ifEmpty { listOf(1, 3, 6, 9, 2, 7, 8, 4, 5, 10) }

                val settings = WatchSettings(
                    brightness = brightness,
                    screenTimeoutSeconds = timeout,
                    dnd = DndSettings(
                        enabled = dndRaw.enabled,
                        startHour = dndRaw.startHour, startMin = dndRaw.startMin,
                        endHour = dndRaw.endHour, endMin = dndRaw.endMin,
                    ),
                    vibrationMode = vibration,
                    metricUnits = metric,
                    powerSaveMode = powerSave,
                    switchMask = switchMask,
                    pageOrder = pageOrder,
                )
                originalSettings = settings
                _state.update { it.copy(isLoading = false, settings = settings) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = "Failed to read settings: ${e.message}") }
            }
        }
    }

    fun update(fn: WatchSettings.() -> WatchSettings) {
        _state.update { it.copy(settings = it.settings.fn(), saveSuccess = false) }
    }

    fun save() {
        if (!bleManager.connectionState.value.isConnected) {
            _state.update { it.copy(error = "Watch not connected") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveSuccess = false, error = null) }
            val s = _state.value.settings
            val o = originalSettings
            runCatching {
                // Only write fields that actually changed.
                // Each call uses sendAndAwait so we get a confirmed response (SET_RESPONSE)
                // and any write failure raises an exception rather than being silently dropped.
                if (s.brightness != o.brightness)
                    bleManager.sendAndAwait(BrightnessCommand.CMD, Action.SET, BrightnessCommand.setPayload(s.brightness))
                if (s.screenTimeoutSeconds != o.screenTimeoutSeconds)
                    bleManager.sendAndAwait(ScreenTimeoutCommand.CMD, Action.SET, ScreenTimeoutCommand.setPayload(s.screenTimeoutSeconds))
                if (s.dnd != o.dnd)
                    bleManager.sendAndAwait(DoNotDisturbCommand.CMD, Action.SET,
                        DoNotDisturbCommand.setPayload(s.dnd.enabled, s.dnd.startHour, s.dnd.startMin, s.dnd.endHour, s.dnd.endMin))
                if (s.vibrationMode != o.vibrationMode)
                    bleManager.sendAndAwait(VibrationCommand.CMD, Action.SET, VibrationCommand.setPayload(s.vibrationMode))
                if (s.metricUnits != o.metricUnits)
                    bleManager.sendAndAwait(UnitCommand.CMD, Action.SET, UnitCommand.setPayload(s.metricUnits))
                if (s.powerSaveMode != o.powerSaveMode)
                    bleManager.sendAndAwait(WorkModeCommand.CMD, Action.SET, WorkModeCommand.setPayload(s.powerSaveMode))
                if (s.switchMask != o.switchMask)
                    bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.SET, SwitchSettingCommand.setPayload(s.switchMask))
                if (s.pageOrder != o.pageOrder)
                    bleManager.sendAndAwait(AppSettingCommand.CMD, Action.SET, AppSettingCommand.setPayload(s.pageOrder))
                if (s.stepGoal != o.stepGoal)
                    bleManager.sendAndAwait(GoalCommand.CMD, Action.SET, GoalCommand.setStepsPayload(s.stepGoal))
                if (s.calorieGoal != o.calorieGoal)
                    bleManager.sendAndAwait(GoalCommand.CMD, Action.SET, GoalCommand.setCaloriesPayload(s.calorieGoal))

                originalSettings = s
                _state.update { it.copy(isSaving = false, saveSuccess = true) }
            }.onFailure { e ->
                _state.update { it.copy(isSaving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun findWatch() {
        bleManager.writeToChar(ControlDeviceCommand.buildFindWatch(), bleManager.commandWriteChar)
    }

    fun toggleNotificationBit(bit: Int, enabled: Boolean) {
        val current = _state.value.settings.switchMask
        update { copy(switchMask = if (enabled) current or bit else current and bit.inv()) }
    }

    fun reorderPages(from: Int, to: Int) {
        val current = _state.value.settings.pageOrder.toMutableList()
        if (from in current.indices && to in current.indices) {
            current.add(to, current.removeAt(from))
            update { copy(pageOrder = current) }
        }
    }
}
