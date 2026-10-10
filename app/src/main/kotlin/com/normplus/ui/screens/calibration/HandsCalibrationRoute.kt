package com.normplus.ui.screens.calibration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.R
import com.normplus.ui.components.ScreenScaffold

/**
 * Hands calibration (#105), a full-screen flow (`FlowScaffold`, which prints the connection
 * banner under its title). Leaving early, by Close or system Back, re-locks the hands.
 *
 * @param onClose leave the flow, back to Watch.
 *
 * Stub (#97): the old calibration screen.
 */
@Composable
fun HandsCalibrationRoute(onClose: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.calibration_title), onBack = onClose) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            HandsCalibrationScreen(viewModel = hiltViewModel(), onBack = onClose)
        }
    }
}
