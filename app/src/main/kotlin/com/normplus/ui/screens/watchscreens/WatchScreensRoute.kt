package com.normplus.ui.screens.watchscreens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.ui.components.rememberWatchTime

/**
 * The order of the watch's own screens (#103), below Watch. It draws `ScreenScaffold` (the large
 * title with Back, and the status pill or the banner under it), with Done in its actions. The
 * new order is sent once, on Done or on leaving with a change; a send that fails keeps the
 * screen open with Retry, or Leave without sending.
 *
 * @param onBack back to Watch, after Done or system Back.
 */
@Composable
fun WatchScreensRoute(onBack: () -> Unit) {
    val viewModel: WatchScreensViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now = rememberWatchTime()
    val actions = remember(viewModel, onBack) {
        WatchScreensActions(
            onBack = { viewModel.send(onBack) },
            onDone = { viewModel.send(onBack) },
            onMove = viewModel::move,
            onRetry = { viewModel.send(onBack) },
            onLeaveWithoutSending = { viewModel.discard(); onBack() },
        )
    }
    // Leaving with a change sends it first, as Done does.
    BackHandler(enabled = state.changed) { actions.onBack() }
    WatchScreensContent(state = state, time = now.toLocalTime(), date = now.toLocalDate(), actions = actions)
}
