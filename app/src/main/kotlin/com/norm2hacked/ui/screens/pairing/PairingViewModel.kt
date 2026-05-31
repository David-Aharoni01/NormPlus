package com.norm2hacked.ui.screens.pairing

import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.ble.BleConnectionState
import com.norm2hacked.ble.BleManager
import com.norm2hacked.data.preferences.WatchPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "PairingViewModel"

data class PairingUiState(
    val isScanning: Boolean = false,
    val scanResults: List<BluetoothDevice> = emptyList(),
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val isConnected: Boolean = false,
    val macError: String? = null,
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private val scanResultMap = linkedMapOf<String, BluetoothDevice>()
    private var scanCollectJob: Job? = null

    init {
        viewModelScope.launch {
            bleManager.connectionState.collect { cs ->
                _state.update { it.copy(connectionState = cs) }
                when (cs) {
                    is BleConnectionState.Ready -> {
                        watchPreferences.saveDeviceMac(cs.device.address)
                        _state.update { it.copy(isConnected = true) }
                    }
                    is BleConnectionState.Disconnected,
                    is BleConnectionState.Error -> {
                        _state.update { it.copy(isScanning = false) }
                    }
                    else -> Unit
                }
            }
        }
        startScan()
    }

    fun startScan() {
        scanResultMap.clear()
        scanCollectJob?.cancel()
        _state.update { it.copy(isScanning = true, scanResults = emptyList(), macError = null) }

        // Collect scan results — BleManager.startScan() emits each discovered device here
        scanCollectJob = viewModelScope.launch {
            bleManager.scanResultFlow.collect { device ->
                Log.d(TAG, "scan result received: ${device.name ?: "null"}  ${device.address}")
                if (scanResultMap.put(device.address, device) == null) {
                    _state.update { it.copy(scanResults = scanResultMap.values.toList()) }
                }
            }
        }

        bleManager.startScan()
    }

    fun connectTo(device: BluetoothDevice) {
        Log.i(TAG, "connectTo: ${device.address}")
        bleManager.stopScan()
        scanCollectJob?.cancel()
        _state.update { it.copy(isScanning = false) }
        viewModelScope.launch { bleManager.connect(device.address) }
    }

    /**
     * Parse a QR string from the watch's QR code and connect directly by the embedded MAC.
     *
     * The watch QR format (from ConnectQRCodePairFragment.smali) is either:
     *   - Simple: just the BLE device name (e.g. "Norm 2")
     *   - Full:   "Info=<name>|<MAC_no_colon_upper>|<id>|<version>|<deviceType>"
     *
     * If [qrOrMac] doesn't look like a QR string, it is treated as a raw MAC address
     * (e.g. "AA:BB:CC:DD:EE:FF") typed manually by the user.
     */
    fun connectByQrOrMac(qrOrMac: String) {
        val input = qrOrMac.trim()
        val mac: String? = when {
            // Full QR format: Info=<name>|<mac_nocolon>|...
            input.contains("Info=") && input.contains("|") -> {
                val parts = input.split("|")
                if (parts.size >= 2) formatMac(parts[1].uppercase()) else null
            }
            // Raw 12-hex-char MAC without colons (from QR or manual entry)
            input.matches(Regex("[0-9A-Fa-f]{12}")) -> formatMac(input.uppercase())
            // Standard colon-separated MAC
            input.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) -> input.uppercase()
            else -> null
        }

        if (mac == null) {
            Log.w(TAG, "connectByQrOrMac: cannot parse '$input'")
            _state.update { it.copy(macError = "Invalid MAC or QR format") }
            return
        }

        Log.i(TAG, "connectByQrOrMac: resolved MAC=$mac")
        _state.update { it.copy(macError = null) }
        bleManager.stopScan()
        scanCollectJob?.cancel()
        _state.update { it.copy(isScanning = false) }
        viewModelScope.launch { bleManager.connect(mac) }
    }

    /** Convert a 12-char hex string to "AA:BB:CC:DD:EE:FF" format. */
    private fun formatMac(hex: String): String? {
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    override fun onCleared() {
        bleManager.stopScan()
        scanCollectJob?.cancel()
    }
}
