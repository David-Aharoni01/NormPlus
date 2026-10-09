package com.normplus.ui.screens.firmware

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleManager
import com.normplus.ble.BleOtaTransport
import com.normplus.data.preferences.WatchPreferences
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.commands.DeviceVersionCommand
import com.normplus.protocol.ota.ApolloOta
import com.normplus.protocol.ota.ApolloOtaSession
import com.normplus.protocol.ota.OtaException
import com.normplus.protocol.ota.OtaImage
import com.normplus.protocol.ota.OtaProgress
import com.normplus.protocol.ota.OtaStep
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val otaTransport: BleOtaTransport,
    private val watchPreferences: WatchPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(FirmwareUiState())
    val state: StateFlow<FirmwareUiState> = _state.asStateFlow()

    /**
     * The bundled update is the watch's resource image, unmodified: update type 4, the
     * resource partition, which the firmware receives itself and which leaves the main
     * firmware untouched. A main-firmware (type 1) update is refused by ApolloOtaSession.
     */
    val bundledVersion = "R0.4"

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
        // Read from assets -- never a file:// URI, which ContentResolver rejects on Android 7+.
        flash(BUNDLED_RESOURCES) {
            // Copied in from the private NORM submodule at build time (app/build.gradle.kts).
            runCatching { context.assets.open("firmware/$BUNDLED_RESOURCES").use { it.readBytes() } }
                .getOrElse { throw OtaException("This build does not include the resource image " +
                    "(it comes from the NORM submodule); choose the file instead") }
        }
    }

    fun flashCustom() {
        val uri = _state.value.customFileUri ?: return
        flash(_state.value.customFileName.orEmpty()) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw OtaException("Cannot read the chosen file")
        }
    }

    /** One update of the file called [name]; its type comes from the name, as the app decides it. */
    private fun flash(name: String, read: () -> ByteArray) {
        if (_state.value.isFlashing) return
        viewModelScope.launch {
            _state.update { it.copy(isFlashing = true, error = null, progress = OtaProgress(OtaStep.UPGRADE_MODE)) }
            try {
                if (!bleManager.connectionState.value.isConnected) throw OtaException("The watch is not connected")
                val image = withContext(Dispatchers.IO) { OtaImage.parse(read()) }
                ApolloOtaSession(otaTransport).flash(image, ApolloOta.updateTypeFor(name)).collect { progress ->
                    _state.update { it.copy(progress = progress) }
                    if (progress.isDone) {
                        _state.update { it.copy(isFlashing = false) }
                        loadWatchVersion()
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isFlashing = false, error = e.message, progress = OtaProgress(OtaStep.FAILED, errorMessage = e.message)) }
            }
        }
    }

    private companion object {
        const val BUNDLED_RESOURCES = "Picture_P03B_NORM2_0.4.bin"
    }
}
