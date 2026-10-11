package com.normplus.ui.screens.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.ui.components.rememberWatchTime

/**
 * Hands calibration (#105), a full-screen flow on `FlowScaffold` (the connection banner under
 * its title): line the minute hand, then the hour hand, up at twelve, then Save sets the time
 * and the watch sweeps them to it.
 *
 * The commands are [HandsCalibrationViewModel]'s, verified on the physical watch and
 * unchanged by the redesign: speed up and UNLOCK once connected; `WatchMoveOne` (0xB6) to
 * nudge and `WatchMoveKeep` (0xB8) to hold; Save sends one DATETIME with byte[8]=1, then
 * LOCK. Leaving early in any way (Close, system Back, the screen going away with the app)
 * disposes this route, which sends STOP and LOCK, so the hands are never left free.
 *
 * @param onClose leave the flow, back to Watch.
 */
@Composable
fun HandsCalibrationRoute(onClose: () -> Unit) {
    val viewModel: HandsCalibrationViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = rememberWatchTime()

    // Arm (speed up + unlock) once connected; re-fires if the connection arrives later.
    LaunchedEffect(state.isConnected) { viewModel.onStart() }
    // Always re-lock the hands if the flow is left without saving.
    DisposableEffect(viewModel) { onDispose { viewModel.onLeave() } }

    val actions = remember(viewModel, onClose) {
        CalibrationActions(
            onClose = onClose,
            onNudge = viewModel::nudge,
            onHoldStart = viewModel::startRotate,
            onHoldEnd = viewModel::stopRotate,
            onNext = viewModel::next,
            onBack = viewModel::back,
            onSave = viewModel::save,
            onRestart = viewModel::restart,
        )
    }
    HandsCalibrationContent(state = state, time = now.toLocalTime(), actions = actions)
}
