package com.normplus.ui.screens.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.ui.screens.activity.ActivityHistoryScreen
import java.time.LocalDate

/**
 * History (#100), the second tab. The shell draws its header (the large "History" title, the status
 * pill or the banner) and the floating navigation capsule; this fills the screen and
 * scrolls under both.
 *
 * @param contentPadding keeps the content clear of the header at its top and of the capsule
 *   and the system's navigation bar at its bottom: a list's contentPadding, plus the gutter.
 * @param onOpenDay a day's row: opens that day in Day detail.
 *
 * Stub (#97): the old activity history. Its workout rows open nothing: the Workouts screens are
 * dropped, and #100 deletes them.
 */
@Composable
fun HistoryRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        ActivityHistoryScreen(viewModel = hiltViewModel(), onWorkoutClick = {})
    }
}
