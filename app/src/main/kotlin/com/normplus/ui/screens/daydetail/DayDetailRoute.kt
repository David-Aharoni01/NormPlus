package com.normplus.ui.screens.daydetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.ui.shell.LocalWatchStatus
import java.time.LocalDate

/**
 * Day detail (#101): a past day's hero card and tiles, the same anatomy as Today without the
 * live status, then its steps by the half hour, its heart-rate readings and the night's
 * sleep. It draws `ScreenScaffold`: the large title with Back, and the status pill or the
 * banner under it. Moving to the previous or next day happens inside the screen (the
 * header's arrows, or a swipe), along the shared axis between days.
 *
 * @param date the day to open first.
 * @param onBack back to the tab it was opened from.
 */
@Composable
fun DayDetailRoute(date: LocalDate, onBack: () -> Unit) {
    val viewModel: DayDetailViewModel = hiltViewModel()
    LaunchedEffect(viewModel, date) { viewModel.open(date) }
    val state by viewModel.state.collectAsState()
    DayDetailContent(
        state = state,
        lastSyncEpochMs = LocalWatchStatus.current.lastSyncEpochMs,
        onBack = onBack,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onRetry = viewModel::retry,
    )
}
