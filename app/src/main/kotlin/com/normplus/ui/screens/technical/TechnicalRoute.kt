package com.normplus.ui.screens.technical

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.status.Fix
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.rememberStatusFixes

/**
 * Technical (#107), at the bottom of Watch: the details ordinary users need not see. It draws
 * `ScreenScaffold`: the large title with Back, and the status pill or the banner under it.
 *
 * @param onBack back to Watch.
 * @param onUpdateFromFile a `.bin` was chosen for the custom-file update: the shell opens the
 *   firmware flow (#106) with it, so the update goes through the same deliberate steps.
 */
@Composable
fun TechnicalRoute(onBack: () -> Unit, onUpdateFromFile: (Uri) -> Unit) {
    val viewModel: TechnicalViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fix = rememberStatusFixes()
    // Any file: a firmware image has no registered type. The flow checks what it is.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onUpdateFromFile(uri)
    }
    val actions = remember(viewModel, fix, onBack) {
        TechnicalActions(
            onBack = onBack,
            onStartService = { fix(Fix.StartService) },
            onStartOnBoot = viewModel::setStartOnBoot,
            onChooseFile = { pick.launch(arrayOf("*/*")) },
        )
    }
    TechnicalContent(state = state, status = LocalWatchStatus.current, actions = actions)
}
