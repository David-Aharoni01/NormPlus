package com.norm2hacked.ui.screens.calibration

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norm2hacked.ble.BleManager
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.CalibrationSaveCommand
import com.norm2hacked.protocol.commands.HandMode
import com.norm2hacked.protocol.commands.KeepAction
import com.norm2hacked.protocol.commands.TranSpeedCommand
import com.norm2hacked.protocol.commands.WatchMoveCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Steps per +/- nudge. On-device 1 didn't move (below the motor's threshold) and 3 was fine, so
// 2 is the smallest reliably-visible step; coarse travel uses hold-to-rotate.
private const val STEP_AMOUNT = 2

// After the re-home DATETIME, wait before LOCK so the hands can finish sweeping to the new time
// (locking mid-move can halt it).
private const val REHOME_SETTLE_MS = 4000L

private const val TAG = "CalibrationVM"

data class CalibrationUiState(
    // Norm 2 has only minute + hour hands (no second hand). Verified on-device.
    val hands: List<HandMode> = listOf(HandMode.MINUTE, HandMode.HOUR),
    val stepIndex: Int = 0,
    val isConnected: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val currentHand: HandMode get() = hands[stepIndex]
    val stepNumber: Int get() = stepIndex + 1
    val total: Int get() = hands.size
    val isLast: Boolean get() = stepIndex == hands.lastIndex
    val isFirst: Boolean get() = stepIndex == 0
}

/**
 * Drives hybrid-watch hand calibration: UNLOCK the hands on entry, let the user move each hand
 * (minute → hour → second) to 12:00 with nudge/hold-rotate, then Save = set the current time and
 * LOCK. LOCK is guaranteed on every exit so the hands are never left free (see [onLeave]).
 */
@HiltViewModel
class HandsCalibrationViewModel @Inject constructor(
    private val bleManager: BleManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    private var started = false
    private var lastRotateClockwise = true

    init {
        viewModelScope.launch {
            bleManager.connectionState.collect { s ->
                _state.update { it.copy(isConnected = s.isConnected) }
            }
        }
    }

    /** Enter calibration: speed up the hands and unlock them so they can be moved. Runs once,
     *  and only once actually connected (so opening the screen offline then connecting still arms). */
    fun onStart() {
        if (started) return
        if (!bleManager.connectionState.value.isConnected) return
        started = true
        viewModelScope.launch {
            bleManager.sendCommandNoResponse(
                CommandCode.TRAN_SPEED, Action.SET, TranSpeedCommand.setPayload(TranSpeedCommand.FAST),
                urgent = true,
            )
            sendKeep(KeepAction.UNLOCK)
        }
    }

    /** One small step of the current hand (+ = clockwise). */
    fun nudge(clockwise: Boolean) {
        val mode = _state.value.currentHand
        viewModelScope.launch {
            bleManager.sendCommandNoResponse(
                CommandCode.WATCH_MOVE_ONE, Action.SET,
                WatchMoveCommand.moveOnePayload(mode, clockwise, STEP_AMOUNT),
                urgent = true,
            )
        }
    }

    /** Begin continuous rotation of the current hand (for large travel); pair with [stopRotate]. */
    fun startRotate(clockwise: Boolean) {
        lastRotateClockwise = clockwise
        viewModelScope.launch { sendKeep(KeepAction.START, clockwise) }
    }

    fun stopRotate() {
        viewModelScope.launch { sendKeep(KeepAction.STOP, lastRotateClockwise) }
    }

    fun next() {
        if (_state.value.isLast) return
        _state.update { it.copy(stepIndex = it.stepIndex + 1) }
    }

    fun back() {
        if (_state.value.isFirst) return
        _state.update { it.copy(stepIndex = it.stepIndex - 1) }
    }

    /** Persist: set the watch to the real current time (drives hands from 12:00), then lock them. */
    fun save() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            // A single DATETIME with byte[8]=1 both sets the clock and drives the hands from their
            // current 12:00 position to that time (matches completeCalibration). Then lock them in;
            // a short settle lets the sweep finish before LOCK.
            val reHome = CalibrationSaveCommand.dateTimePayload(reHome = true)
            Log.i(TAG, "save: re-home payload=${reHome.joinToString(" ") { "%02X".format(it) }}")
            bleManager.sendCommandNoResponse(CommandCode.DATETIME, Action.SET, reHome, urgent = true)
            kotlinx.coroutines.delay(REHOME_SETTLE_MS)
            Log.i(TAG, "save: locking hands")
            sendKeep(KeepAction.LOCK)
            _state.update { it.copy(saving = false, done = true) }
        }
    }

    /**
     * Called when the screen leaves without saving (back-out, nav-away). Relock the hands so the
     * watch is never left with free hands. No-op once [save] has already locked.
     */
    fun onLeave() {
        if (_state.value.done) return
        viewModelScope.launch {
            sendKeep(KeepAction.STOP)   // cancel any in-flight continuous move
            sendKeep(KeepAction.LOCK)
        }
    }

    private suspend fun sendKeep(action: KeepAction, clockwise: Boolean = true) {
        bleManager.sendCommandNoResponse(
            CommandCode.WATCH_MOVE_KEEP, Action.SET,
            WatchMoveCommand.keepPayload(_state.value.currentHand, clockwise, action),
            urgent = true,
        )
    }
}
