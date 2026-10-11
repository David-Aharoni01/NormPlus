package com.normplus.ui.screens.notificationapps

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.R
import com.normplus.status.Blocker
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.rememberStatusFixes

/**
 * Notification apps (#104), below Watch (and opened from the first run): which apps may send
 * their notifications to the watch. It draws `ScreenScaffold`: the large title with Back, and
 * the status pill or the banner under it.
 *
 * Notification access comes from the shared status (sampled again whenever the app comes back
 * from Android's settings); its fix opens Android's notification access page.
 *
 * @param onBack back to where it was opened from.
 */
@Composable
fun NotificationAppsRoute(onBack: () -> Unit) {
    val viewModel: NotificationAppsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accessOff = Blocker.NotificationAccessOff in LocalWatchStatus.current.blockers
    val fixes = rememberStatusFixes()
    val snackbar = remember { SnackbarHostState() }

    val failed = state.saveFailed
    val failedText = failed?.let { stringResource(R.string.notificationapps_save_failed, it.label) }
    LaunchedEffect(failed) {
        if (failedText != null) {
            snackbar.showSnackbar(failedText)
            viewModel.clearSaveFailed()
        }
    }

    val actions = remember(viewModel, fixes) {
        NotificationAppsActions(
            onQueryChange = viewModel::setQuery,
            onShowSystemAppsChange = viewModel::setShowSystemApps,
            onEnabledChange = viewModel::setEnabled,
            onSuppressDuplicatesChange = viewModel::setSuppressDuplicates,
            onRetryLoad = viewModel::load,
            onTurnOnAccess = { fixes(Blocker.NotificationAccessOff.fix) },
        )
    }

    NotificationAppsContent(
        state = state,
        accessOff = accessOff,
        actions = actions,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    )
}
