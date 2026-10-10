package com.normplus.ui.screens.watchscreens

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleManager
import com.normplus.domain.model.WatchSettings
import com.normplus.protocol.Action
import com.normplus.protocol.commands.AppSettingCommand
import com.normplus.ui.components.SendState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WatchScreensVM"

/**
 * The order of the watch's pages (#103): [order] is the page types after the face, as
 * AppSettingCommand carries them. Every page is always sent: whether a page can be hidden by
 * leaving it out is not established from the smali (setPageQueue sends whatever array it is
 * given), so Norm+ reorders and does not hide (an open question for the owner).
 */
@Immutable
data class WatchScreensUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val order: List<Int> = WatchSettings().pageOrder,
    /** What the watch holds, as last read or last sent. */
    val sentOrder: List<Int> = WatchSettings().pageOrder,
    val send: SendState? = null,
    val readFailed: Boolean = false,
) {
    val changed: Boolean get() = order != sentOrder
}

@HiltViewModel
class WatchScreensViewModel @Inject constructor(private val bleManager: BleManager) : ViewModel() {

    private val _state = MutableStateFlow(WatchScreensUiState())
    val state: StateFlow<WatchScreensUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            bleManager.connectionState.map { it.isConnected }.distinctUntilChanged().collect { connected ->
                _state.update { it.copy(connected = connected) }
                if (connected && !_state.value.changed) load()
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val pages = AppSettingCommand.parse(
                    bleManager.sendAndAwait(AppSettingCommand.CMD, Action.CHECK, AppSettingCommand.queryPayload()),
                )
                _state.update { s ->
                    val read = pages.ifEmpty { s.sentOrder }
                    s.copy(loading = false, readFailed = false, order = read, sentOrder = read)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "load: ${e.javaClass.simpleName}: ${e.message}")
                _state.update { it.copy(loading = false, readFailed = true) }
            }
        }
    }

    /** Moves the page at [from] to [to] (both indexes into [WatchScreensUiState.order]). */
    fun move(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.order.indices || to !in s.order.indices || from == to) return@update s
            val next = s.order.toMutableList().apply { add(to, removeAt(from)) }
            s.copy(order = next, send = null)
        }
    }

    /**
     * Sends the order once (Done, or leaving with a change). [onSent] runs when the watch
     * acknowledged it; on failure the screen stays, with Retry.
     */
    fun send(onSent: () -> Unit) {
        val s = _state.value
        if (!s.changed) { onSent(); return }
        if (!bleManager.isConnected) {
            _state.update { it.copy(send = SendState.NotSent) }
            return
        }
        _state.update { it.copy(send = SendState.Sending) }
        viewModelScope.launch {
            try {
                bleManager.sendAndAwait(AppSettingCommand.CMD, Action.SET, AppSettingCommand.setPayload(s.order))
                _state.update { it.copy(send = SendState.Sent, sentOrder = s.order) }
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "send: ${e.javaClass.simpleName}: ${e.message}")
                _state.update { it.copy(send = SendState.NotSent) }
            }
        }
    }

    /** Leave without sending: back to what the watch holds. */
    fun discard() {
        _state.update { it.copy(order = it.sentOrder, send = null) }
    }
}
