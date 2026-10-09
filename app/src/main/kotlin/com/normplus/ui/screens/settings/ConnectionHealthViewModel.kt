package com.normplus.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleConnectionState
import com.normplus.ble.BleManager
import com.normplus.ble.BleService
import com.normplus.data.preferences.WatchPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only view of everything that decides whether the watch stays connected, plus the actions
 * that fix each blocker. Deliberately surfaces *causes* (Bluetooth off, permission revoked, the app
 * restricted in the background, no battery-optimisation exemption) rather than a single
 * "disconnected" — a silent disconnect the user can't explain is the problem this screen exists for.
 *
 * System-level facts (battery-optimisation exemption, background restriction, permissions) are not
 * observable as flows, so they are sampled by [refresh]; the UI calls it on entry and again after
 * returning from any system dialog.
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
    private val bleManager: BleManager,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionHealthUiState())
    val state: StateFlow<ConnectionHealthUiState> = _state.asStateFlow()

    init {
        bleManager.connectionState
            .onEach { s -> _state.update { it.copy(connectionLabel = label(s), connected = s.isConnected) } }
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
        watchPreferences.batteryPromptDismissed
            .onEach { dismissed -> _state.update { it.copy(batteryPromptDismissed = dismissed) } }
            .launchIn(viewModelScope)
        refresh()
    }

    /** Re-sample the system-level facts (call on screen entry and after any system dialog). */
    fun refresh() {
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        _state.update {
            it.copy(
                bluetoothOn = BleService.isBluetoothOn(context),
                hasBlePermission = BleService.hasBleConnectPermission(context),
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                backgroundRestricted = activity?.isBackgroundRestricted ?: false,
                ignoringBatteryOptimizations =
                    power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            )
        }
    }

    /** Explicit user intent — also clears a previous "Disconnect" so auto-start is armed again. */
    fun startService() = BleService.start(context)

    fun dismissBatteryPrompt() {
        viewModelScope.launch { watchPreferences.setBatteryPromptDismissed(true) }
    }

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

    private fun label(state: BleConnectionState): String = when (state) {
        is BleConnectionState.Ready -> "Connected to ${state.deviceName}"
        is BleConnectionState.Connecting -> "Connecting…"
        is BleConnectionState.Discovering -> "Setting up…"
        is BleConnectionState.Scanning -> "Scanning…"
        is BleConnectionState.Error ->
            if (state.retryCount > 0) "Reconnecting (attempt ${state.retryCount})" else state.message
        is BleConnectionState.Disconnected -> "Disconnected"
    }
}
