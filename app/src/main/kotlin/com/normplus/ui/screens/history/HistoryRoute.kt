package com.normplus.ui.screens.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.normplus.ui.shell.LocalWatchStatus
import java.time.LocalDate

/**
 * History (#100), the second tab. The shell draws its header (the large "History" title, the status
 * pill or the banner) and the floating navigation capsule; this fills the screen and
 * scrolls under both.
 *
 * @param contentPadding keeps the content clear of the header at its top and of the capsule
 *   and the system's navigation bar at its bottom: a list's contentPadding, plus the gutter.
 * @param onOpenDay a day's row: opens that day in Day detail.
 */
@Composable
fun HistoryRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val status = LocalWatchStatus.current
    HistoryContent(
        state = state,
        contentPadding = contentPadding,
        canSync = status.isReady && !status.isSyncing,
        onSelectMetric = viewModel::selectMetric,
        onSelectRange = viewModel::selectRange,
        onOpenDay = onOpenDay,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onSync = viewModel::sync,
    )
}
