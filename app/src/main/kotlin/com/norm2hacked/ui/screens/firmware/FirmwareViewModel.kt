package com.norm2hacked.ui.screens.firmware

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import com.norm2hacked.ble.BleManager
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.DeviceVersionCommand
import com.norm2hacked.protocol.commands.UpgradeModeCommand
import com.norm2hacked.protocol.ota.ApolloOtaProtocol
import com.norm2hacked.protocol.ota.OtaProgress
import com.norm2hacked.protocol.ota.OtaStep
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FirmwareUiState(
    val watchVersion: String = "",
    val isLoading: Boolean = false,
    val isFlashing: Boolean = false,
    val progress: OtaProgress? = null,
    val customFileUri: Uri? = null,
    val customFileName: String? = null,
    val error: String? = null,
)

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val otaProtocol: ApolloOtaProtocol,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(FirmwareUiState())
    val state: StateFlow<FirmwareUiState> = _state.asStateFlow()

    val bundledVersion = "F0.2B01"

    init {
        loadWatchVersion()
    }

    private fun loadWatchVersion() {
        viewModelScope.launch {
            val ver = watchPreferences.deviceVersion.first()
            if (ver.isNotEmpty()) _state.update { it.copy(watchVersion = ver) }
        }
        if (!bleManager.connectionState.value.isConnected) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            runCatching {
                val pkt = bleManager.sendAndAwait(CommandCode.DEVICE_VERSION, Action.CHECK, byteArrayOf(1))
                val ver = DeviceVersionCommand.parseVersionString(pkt)
                watchPreferences.saveDeviceVersion(ver)
                _state.update { it.copy(watchVersion = ver, isLoading = false) }
            }.onFailure { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun setCustomFile(uri: Uri, name: String) {
        _state.update { it.copy(customFileUri = uri, customFileName = name) }
    }

    fun flashBundled() {
        // Read directly from assets — never create a file:// URI, which ContentResolver
        // rejects on Android 7+ without FileProvider.
        flash(assetName = "firmware/Apollo3_P03B_NORM2_F0.2B01.bin")
    }

    fun flashCustom() {
        val uri = _state.value.customFileUri ?: return
        flash(uri = uri)
    }

    private fun flash(uri: Uri? = null, assetName: String? = null) {
        if (_state.value.isFlashing) return
        viewModelScope.launch {
            _state.update { it.copy(isFlashing = true, error = null, progress = OtaProgress(OtaStep.BT_PARAM)) }
            try {
                bleManager.writeToChar(UpgradeModeCommand.buildSet(), com.norm2hacked.ble.BleConstants.CHAR_WRITE_8001)
                bleManager.waitForDfuService()
                val progressFlow = when {
                    assetName != null -> otaProtocol.flashAsset(assetName)
                    uri != null -> otaProtocol.flash(uri)
                    else -> return@launch
                }
                progressFlow.collect { progress ->
                    _state.update { it.copy(progress = progress) }
                    if (progress.isDone || progress.isFailed) {
                        _state.update { it.copy(isFlashing = false) }
                        if (progress.isDone) loadWatchVersion()
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isFlashing = false, error = e.message, progress = OtaProgress(OtaStep.FAILED, errorMessage = e.message)) }
            }
        }
    }
}
