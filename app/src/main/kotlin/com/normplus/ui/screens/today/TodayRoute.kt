package com.normplus.ui.screens.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.ui.screens.dashboard.DashboardScreen
import java.time.LocalDate

/**
 * Today (#99), the first tab. The shell draws its header (the large "Today" title, the status pill
 * or the banner, and the Sync action, which runs `WatchStatusSource.sync()`) and the floating
 * navigation capsule; this fills the screen and scrolls under
 * both. Pull to refresh calls `WatchStatusSource.sync()` too.
 *
 * @param contentPadding keeps the content clear of the header at its top and of the capsule
 *   and the system's navigation bar at its bottom: a list's contentPadding, plus the gutter.
 * @param onOpenDay the Yesterday row: opens that day in Day detail.
 *
 * Stub (#97): the old dashboard.
 */
@Composable
fun TodayRoute(contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        DashboardScreen(viewModel = hiltViewModel())
    }
}
