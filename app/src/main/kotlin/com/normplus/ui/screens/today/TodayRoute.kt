package com.normplus.ui.screens.today

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

/**
 * Today (#99), the first tab. The shell draws its header (the large "Today" title, the status pill
 * or the banner, and the Sync action, which runs `WatchStatusSource.sync()`) and the floating
 * navigation capsule; this fills the screen and scrolls under both. Pull to refresh calls
 * `WatchStatusSource.sync()` too.
 *
 * @param contentPadding keeps the content clear of the header at its top and of the capsule
 *   and the system's navigation bar at its bottom: a list's contentPadding, plus the gutter.
 * @param onOpenDay the Yesterday row: opens that day in Day detail.
 */
@Composable
fun TodayRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val viewModel: TodayViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    TodayContent(
        state = state,
        contentPadding = contentPadding,
        onSync = viewModel::sync,
        onDismissShortfall = viewModel::dismissShortfall,
        onOpenDay = onOpenDay,
    )
}
