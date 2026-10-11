package com.normplus.ui.screens.watch

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleManager
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.NotificationRuleDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.preferences.WatchPreferences
import com.normplus.domain.model.DndSettings
import com.normplus.domain.model.WatchSettings
import com.normplus.protocol.Action
import com.normplus.protocol.commands.AppSettingCommand
import com.normplus.protocol.commands.BrightnessCommand
import com.normplus.protocol.commands.ControlDeviceCommand
import com.normplus.protocol.commands.DoNotDisturbCommand
import com.normplus.protocol.commands.GoalCommand
import com.normplus.protocol.commands.ScreenTimeoutCommand
import com.normplus.protocol.commands.SwitchSettingCommand
import com.normplus.protocol.commands.UnitCommand
import com.normplus.protocol.commands.VibrationCommand
import com.normplus.protocol.commands.WorkModeCommand
import com.normplus.ui.components.SendState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WatchVM"

/** How long a "Sent" mark stays on its row before the row goes quiet again. */
private const val SENT_MARK_MS = 2_500L

/**
 * Watch (#102): reads the watch's settings once connected, and sends each change the moment it
 * is made, one row at a time, each with its own state mark. The commands and payloads are the
 * ones the old settings screen sent (SettingsCommands, from the smali); only when they go has
 * changed, from one Save to each change.
 */
@HiltViewModel
class WatchViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val prefs: WatchPreferences,
    rules: NotificationRuleDao,
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchUiState())
    val state: StateFlow<WatchUiState> = _state.asStateFlow()

    /** The last change asked for on each row, so Retry can send it again. */
    private val pending = mutableMapOf<SettingKey, suspend () -> Unit>()
    private val sentJobs = mutableMapOf<SettingKey, Job>()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            bleManager.connectionState.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                _state.update { it.copy(connected = connected) }
                if (connected) load()
            }
        }
        viewModelScope.launch { prefs.stepGoal.collect { g -> _state.update { it.copy(stepGoal = g) } } }
        viewModelScope.launch { prefs.deleteAfterSync.collect { on -> _state.update { it.copy(deleteAfterSync = on) } } }
        viewModelScope.launch { prefs.deviceVersion.collect { v -> _state.update { it.copy(version = v) } } }
        viewModelScope.launch {
            rules.queryAll().collect { list -> _state.update { it.copy(forwardingApps = list.count { r -> r.enabled }) } }
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** Reads every setting from the watch, as the old settings screen did; a failure keeps what is shown. */
    fun load() {
        if (!bleManager.isConnected) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val brightness = BrightnessCommand.parse(bleManager.sendAndAwait(BrightnessCommand.CMD, Action.CHECK, BrightnessCommand.queryPayload()))
                val timeout = ScreenTimeoutCommand.parse(bleManager.sendAndAwait(ScreenTimeoutCommand.CMD, Action.CHECK, ScreenTimeoutCommand.queryPayload()))
                val dnd = DoNotDisturbCommand.parse(bleManager.sendAndAwait(DoNotDisturbCommand.CMD, Action.CHECK, DoNotDisturbCommand.queryPayload()))
                val vibration = VibrationCommand.parse(bleManager.sendAndAwait(VibrationCommand.CMD, Action.CHECK, VibrationCommand.queryPayload()))
                val metric = UnitCommand.parse(bleManager.sendAndAwait(UnitCommand.CMD, Action.CHECK, UnitCommand.queryPayload()))
                val powerSave = WorkModeCommand.parse(bleManager.sendAndAwait(WorkModeCommand.CMD, Action.CHECK, WorkModeCommand.queryPayload()))
                val mask = SwitchSettingCommand.parse(bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.CHECK, SwitchSettingCommand.queryPayload()))
                val pages = AppSettingCommand.parse(bleManager.sendAndAwait(AppSettingCommand.CMD, Action.CHECK, AppSettingCommand.queryPayload()))
                _state.update { s ->
                    s.copy(
                        loading = false,
                        readAtEpochMs = System.currentTimeMillis(),
                        settings = s.settings.copy(
                            brightness = brightness,
                            screenTimeoutSeconds = timeout,
                            dnd = DndSettings(dnd.enabled, dnd.startHour, dnd.startMin, dnd.endHour, dnd.endMin),
                            vibrationMode = vibration,
                            metricUnits = metric,
                            powerSaveMode = powerSave,
                            switchMask = mask,
                            pageOrder = pages.ifEmpty { s.settings.pageOrder },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load: ${e.javaClass.simpleName}: ${e.message}")
                _state.update { it.copy(loading = false) }
            }
        }
    }

    // ── Sending, one row at a time ───────────────────────────────────────────

    fun setBrightness(level: Int) = change(SettingKey.Brightness, { copy(brightness = level) }) {
        bleManager.sendAndAwait(BrightnessCommand.CMD, Action.SET, BrightnessCommand.setPayload(level))
    }

    fun setTimeout(seconds: Int) = change(SettingKey.Timeout, { copy(screenTimeoutSeconds = seconds) }) {
        bleManager.sendAndAwait(ScreenTimeoutCommand.CMD, Action.SET, ScreenTimeoutCommand.setPayload(seconds))
    }

    fun setDnd(dnd: DndSettings) = change(SettingKey.Dnd, { copy(dnd = dnd) }) {
        bleManager.sendAndAwait(
            DoNotDisturbCommand.CMD, Action.SET,
            DoNotDisturbCommand.setPayload(dnd.enabled, dnd.startHour, dnd.startMin, dnd.endHour, dnd.endMin),
        )
    }

    fun setVibration(mode: Int) = change(SettingKey.Vibration, { copy(vibrationMode = mode) }) {
        bleManager.sendAndAwait(VibrationCommand.CMD, Action.SET, VibrationCommand.setPayload(mode))
    }

    fun setPowerSave(on: Boolean) = change(SettingKey.PowerSave, { copy(powerSaveMode = on) }) {
        bleManager.sendAndAwait(WorkModeCommand.CMD, Action.SET, WorkModeCommand.setPayload(on))
    }

    fun setMetric(metric: Boolean) = change(SettingKey.Units, { copy(metricUnits = metric) }) {
        bleManager.sendAndAwait(UnitCommand.CMD, Action.SET, UnitCommand.setPayload(metric))
    }

    /** A switch-mask setting: the watch takes the whole mask, so the whole mask goes. */
    fun setBit(key: SettingKey, on: Boolean) {
        val bit = key.maskBit ?: return
        val mask = _state.value.settings.switchMask.let { if (on) it or bit else it and bit.inv() }
        change(key, { copy(switchMask = mask) }) {
            bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.SET, SwitchSettingCommand.setPayload(mask))
        }
    }

    /** The goal Norm+ keeps: saved at once, then sent (and sent again on every connect, WatchStatusSource). */
    fun setStepGoal(steps: Int) {
        viewModelScope.launch { prefs.saveStepGoal(steps) }
        change(SettingKey.StepGoal, { copy(stepGoal = steps) }) {
            bleManager.sendAndAwait(GoalCommand.CMD, Action.SET, GoalCommand.setStepsPayload(steps))
        }
    }

    fun setDeleteAfterSync(on: Boolean) {
        viewModelScope.launch { prefs.setDeleteAfterSync(on) }
    }

    fun retry(key: SettingKey) {
        val send = pending[key] ?: return
        launchSend(key, send)
    }

    fun dismiss(key: SettingKey) {
        pending.remove(key)
        _state.update { it.copy(sends = it.sends - key) }
    }

    /** Vibrates the watch (fire and forget, as the old screen did). */
    fun findWatch() {
        if (!bleManager.isConnected) return
        bleManager.writeToChar(ControlDeviceCommand.buildFindWatch(), bleManager.commandWriteChar)
    }

    /**
     * Forget this watch (#102): Norm+ forgets the saved watch and what it kept about it, then
     * lets the link go. Android's pairing is kept (the owner's decision); the confirm step
     * offers Android's Bluetooth settings for that. The synced history stays, marked as this watch's (#113).
     */
    fun forgetWatch(onDone: () -> Unit) {
        viewModelScope.launch {
            // The history stays, marked as this watch's (#113): claim what was synced before
            // Norm+ kept the watch of each record, so it shows as another watch's from now on.
            prefs.getDeviceMac()?.let { old ->
                runCatching { sportDao.claimUnowned(old); heartRateDao.claimUnowned(old); sleepDao.claimUnowned(old) }
                    .onFailure { Log.w(TAG, "forget: claiming records: ${it.message}") }
            }
            prefs.clearDeviceMac()
            prefs.saveDeviceVersion("")
            runCatching { bleManager.disconnect() }.onFailure { Log.w(TAG, "forget: disconnect: ${it.message}") }
            onDone()
        }
    }

    /** Shows [update] at once, then sends it; the row says sending, then sent or not sent. */
    private fun change(key: SettingKey, update: WatchSettings.() -> WatchSettings, send: suspend () -> Unit) {
        _state.update { it.copy(settings = it.settings.update()) }
        pending[key] = send
        launchSend(key, send)
    }

    private fun launchSend(key: SettingKey, send: suspend () -> Unit) {
        sentJobs.remove(key)?.cancel()
        if (!bleManager.isConnected) {
            mark(key, SendState.NotSent)
            return
        }
        mark(key, SendState.Sending)
        viewModelScope.launch {
            try {
                send()
                pending.remove(key)
                mark(key, SendState.Sent)
                sentJobs[key] = launch {
                    delay(SENT_MARK_MS)
                    _state.update { it.copy(sends = it.sends - key) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "send $key: ${e.javaClass.simpleName}: ${e.message}")
                mark(key, SendState.NotSent)
            }
        }
    }

    private fun mark(key: SettingKey, state: SendState) {
        _state.update { it.copy(sends = it.sends + (key to state)) }
    }
}
