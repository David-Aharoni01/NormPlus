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

/** What the update would send (#106). */
enum class FirmwareTarget {
    /** The watch's own resource image, type 4: the way back. */
    OriginalResources,
    /** A `.bin` chosen on Technical (#107). */
    CustomFile,
}

data class FirmwareUiState(
    val watchVersion: String = "",
    val isLoading: Boolean = false,
    val isFlashing: Boolean = false,
    val progress: OtaProgress? = null,
    val customFileUri: Uri? = null,
    val customFileName: String? = null,
    val error: String? = null,
    /** The update chosen for review; null on the first step. */
    val target: FirmwareTarget? = null,
    /** "I understand the watch shows nothing until this finishes" is ticked. */
    val acknowledged: Boolean = false,
    val connected: Boolean = false,
    /** Seconds to wait before an update may start, after the watch (re)connected; 0 when it may. */
    val settleSeconds: Int = 0,
    /** Whether this build carries the original resource image (it comes from the NORM submodule). */
    val bundledAvailable: Boolean = true,
    /** Time remaining while sending, from how fast the pieces are going; null before that. */
    val remainingSeconds: Int? = null,
) {
    /** Start: a target, the box ticked, the watch connected and past its first seconds, nothing running. */
    val canStart: Boolean get() = target != null && acknowledged && connected && settleSeconds == 0 && !isFlashing
}

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
        _state.update {
            it.copy(bundledAvailable = runCatching { context.assets.list("firmware")?.contains(BUNDLED_RESOURCES) == true }.getOrDefault(false))
        }
        // Not within ~10 s of the watch starting (CLAUDE.md OTA rules, #57). The app cannot see
        // the watch boot, so the guard counts from the moment the link became ready: a watch that
        // just started has just connected.
        viewModelScope.launch {
            var countdown: kotlinx.coroutines.Job? = null
            var sawItDown = false
            bleManager.connectionState.collect { s ->
                val connected = s.isConnected
                if (!connected) sawItDown = true
                if (connected == _state.value.connected) return@collect
                _state.update { it.copy(connected = connected) }
                countdown?.cancel()
                if (connected) {
                    countdown = launch {
                        // Count from when the link became ready (BleService saves it), not from
                        // when this screen opened: a watch connected long ago needs no wait.
                        // Seen connecting here: the full wait, whatever the saved time says yet.
                        val since = if (sawItDown) 0L else (System.currentTimeMillis() - watchPreferences.lastConnectedEpoch.first()) / 1000
                        val wait = (SETTLE_SECONDS - since).coerceIn(0, SETTLE_SECONDS.toLong()).toInt()
                        for (left in wait downTo 1) {
                            _state.update { it.copy(settleSeconds = left) }
                            kotlinx.coroutines.delay(1_000)
                        }
                        _state.update { it.copy(settleSeconds = 0) }
                    }
                } else {
                    _state.update { it.copy(settleSeconds = 0) }
                }
            }
        }
    }

    /** The update to review: the original resources, or a custom file handed over from Technical. */
    fun choose(target: FirmwareTarget?) {
        _state.update { it.copy(target = target, acknowledged = false, error = null, progress = null) }
    }

    fun acknowledge(ticked: Boolean) {
        _state.update { it.copy(acknowledged = ticked) }
    }

    /** Starts the chosen update; [FirmwareUiState.canStart] gates it here as well as on the button. */
    fun start() {
        val s = _state.value
        if (!s.canStart) return
        when (s.target) {
            FirmwareTarget.OriginalResources -> flashBundled()
            FirmwareTarget.CustomFile -> flashCustom()
            null -> Unit
        }
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
            _state.update { it.copy(isFlashing = true, error = null, remainingSeconds = null, progress = OtaProgress(OtaStep.UPGRADE_MODE)) }
            var sendStart = 0L
            var sendStartPiece = 0
            try {
                if (!bleManager.connectionState.value.isConnected) throw OtaException("The watch is not connected")
                val image = withContext(Dispatchers.IO) { OtaImage.parse(read()) }
                ApolloOtaSession(otaTransport).flash(image, ApolloOta.updateTypeFor(name)).collect { progress ->
                    // Time remaining from how fast the pieces are going so far; none before sending.
                    var remaining: Int? = null
                    if (progress.step == OtaStep.DATA_STREAM && progress.packetsTotal > 0) {
                        val now = System.currentTimeMillis()
                        if (sendStart == 0L) { sendStart = now; sendStartPiece = progress.packetsCurrent }
                        val done = progress.packetsCurrent - sendStartPiece
                        val elapsed = now - sendStart
                        if (done > 0 && elapsed > 2_000) {
                            remaining = (((progress.packetsTotal - progress.packetsCurrent) * elapsed / done) / 1000).toInt()
                        }
                    }
                    _state.update { it.copy(progress = progress, remainingSeconds = remaining) }
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
        /** Seconds after the link becomes ready before an update may start (#57). */
        const val SETTLE_SECONDS = 10
    }
}
