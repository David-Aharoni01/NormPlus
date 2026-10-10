package com.normplus.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleService
import com.normplus.data.preferences.WatchPreferences
import com.normplus.status.Blocker
import com.normplus.status.Link
import com.normplus.status.WatchStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Read-only view of everything that decides whether the watch stays connected, plus the actions
 * that fix each blocker. Deliberately surfaces *causes* (Bluetooth off, permission revoked, the app
 * restricted in the background, no battery-optimisation exemption) rather than a single
 * "disconnected" — a silent disconnect the user can't explain is the problem this screen exists for.
 *
 * Every fact comes from [WatchStatusSource] (#97), which samples the system for the whole app;
 * [refresh] asks it to sample again (on entry, and after returning from any system dialog).
 */
data class ConnectionHealthUiState(
    val serviceRunning: Boolean = false,
    val connectionLabel: String = "Disconnected",
    val connected: Boolean = false,
    val lastConnectedEpoch: Long = 0L,
    val autoStartEnabled: Boolean = true,
    val bluetoothOn: Boolean = true,
    val hasBlePermission: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = true,
    val batteryPromptDismissed: Boolean = false,
) {
    /** Only prompt when it would actually help and the user hasn't waved it away before. */
    val showBatteryPrompt: Boolean get() = !ignoringBatteryOptimizations && !batteryPromptDismissed

    /** True when something the user must fix is blocking the connection. */
    val hasBlocker: Boolean get() = !hasBlePermission || !bluetoothOn || backgroundRestricted
}

@HiltViewModel
class ConnectionHealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statusSource: WatchStatusSource,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionHealthUiState())
    val state: StateFlow<ConnectionHealthUiState> = _state.asStateFlow()

    init {
        statusSource.status
            .onEach { s ->
                _state.update {
                    it.copy(
                        connectionLabel = label(s.link),
                        connected = s.isReady,
                        bluetoothOn = Blocker.BluetoothOff !in s.blockers,
                        hasBlePermission = Blocker.BluetoothPermissionMissing !in s.blockers,
                        notificationsEnabled = Blocker.NotificationsBlocked !in s.blockers,
                        backgroundRestricted = Blocker.BackgroundRestricted in s.blockers,
                        ignoringBatteryOptimizations = Blocker.BatteryOptimisationOn !in s.blockers,
                        batteryPromptDismissed = Blocker.BatteryOptimisationOn in s.dismissed,
                    )
                }
            }
            .launchIn(viewModelScope)
        BleService.isRunning
            .onEach { running -> _state.update { it.copy(serviceRunning = running) } }
            .launchIn(viewModelScope)
        watchPreferences.lastConnectedEpoch
            .onEach { epoch -> _state.update { it.copy(lastConnectedEpoch = epoch) } }
            .launchIn(viewModelScope)
        watchPreferences.autoStartEnabled
            .onEach { enabled -> _state.update { it.copy(autoStartEnabled = enabled) } }
            .launchIn(viewModelScope)
        refresh()
    }

    /** Re-sample the system-level facts (call on screen entry and after any system dialog). */
    fun refresh() = statusSource.refresh()

    /** Explicit user intent — also clears a previous "Stop" so auto-start is armed again. */
    fun startService() = BleService.start(context)

    fun dismissBatteryPrompt() = statusSource.dismissBatteryOptimisation()

    /**
     * System dialog that asks the user to exempt us from Doze/battery optimisation. The exemption
     * is what keeps the service alive overnight — and it additionally lifts the Android 12+ ban on
     * starting a foreground service from the background, which the boot/watchdog paths rely on.
     * Falls back to the battery-optimisation list on devices where the direct dialog is missing.
     */
    fun batteryOptimizationIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))

    fun bluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    private fun label(link: Link): String = when (link) {
        Link.Ready -> "Connected"
        Link.Connecting -> "Connecting…"
        Link.SettingUp -> "Setting up…"
        Link.Scanning -> "Scanning…"
        is Link.Reconnecting -> if (link.attempt > 0) "Reconnecting (attempt ${link.attempt})" else "Reconnecting…"
        Link.Disconnected, Link.NoWatch -> "Disconnected"
    }
}
