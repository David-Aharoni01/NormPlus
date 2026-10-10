package com.normplus.ui.screens.firmware

import android.net.Uri
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
 * The firmware update (#106), a full-screen flow (`FlowScaffold`, which prints the connection
 * banner under its title). System Back while an update is sending asks first: a
 * BackHandler here, as the brief says.
 *
 * @param customFile a `.bin` chosen on Technical (#107), or null for the update's own choices.
 * @param onClose leave the flow (back to Watch, or to Technical for a custom file).
 *
 * Stub (#97): the old firmware screen, which still has its own custom-file picker.
 */
@Composable
fun FirmwareRoute(customFile: Uri?, onClose: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.firmware_title), onBack = onClose) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            FirmwareScreen(viewModel = hiltViewModel(), onBack = onClose)
        }
    }
}
