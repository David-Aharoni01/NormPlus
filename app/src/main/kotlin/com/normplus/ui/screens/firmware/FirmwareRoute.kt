package com.normplus.ui.screens.firmware

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The firmware update (#106), a full-screen flow (`FlowScaffold`, which prints the connection
 * banner under its title). System Back while an update is sending asks first.
 *
 * @param customFile a `.bin` chosen on Technical (#107), or null for the update's own choices.
 * @param onClose leave the flow (back to Watch, or to Technical for a custom file).
 */
@Composable
fun FirmwareRoute(customFile: Uri?, onClose: () -> Unit) {
    val viewModel: FirmwareViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(customFile) {
        if (customFile != null) {
            val name = runCatching {
                context.contentResolver.query(customFile, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: customFile.lastPathSegment.orEmpty()
            viewModel.setCustomFile(customFile, name)
            viewModel.choose(FirmwareTarget.CustomFile)
        }
    }
    val actions = remember(viewModel, onClose) {
        FirmwareActions(
            onClose = onClose,
            onChoose = viewModel::choose,
            onAcknowledge = viewModel::acknowledge,
            onStart = viewModel::start,
            // Sending again goes back through the review: the two deliberate acts again.
            onSendAgain = { viewModel.choose(viewModel.state.value.target) },
        )
    }
    FirmwareContent(state = state, actions = actions)
}
