package com.normplus.ui.screens.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.ui.screens.settings.WatchSettingsScreen

/**
 * Watch (#102), the third tab: the watch card, the fix-its, the settings. The shell draws
 * its header (the large "Watch" title, the status pill or the banner) and the floating
 * navigation capsule; this fills the screen and scrolls under
 * both.
 *
 * @param contentPadding keeps the content clear of the header at its top and of the capsule
 *   and the system's navigation bar at its bottom: a list's contentPadding, plus the gutter.
 * @param onOpenNotificationApps Notifications → Notification apps.
 * @param onOpenWatchScreens Watch → the watch's screens order.
 * @param onOpenHandsCalibration Calibrate hands (a full-screen flow).
 * @param onOpenFirmwareUpdate Firmware (a full-screen flow).
 * @param onOpenTechnical Technical, at the bottom.
 * @param onWatchForgotten "Forget this watch" is confirmed and done: the shell goes to the first
 *   run and clears the back stack.
 *
 * Stub (#97): the old settings screen.
 */
@Composable
fun WatchRoute(
    contentPadding: PaddingValues,
    onOpenNotificationApps: () -> Unit,
    onOpenWatchScreens: () -> Unit,
    onOpenHandsCalibration: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit,
    onOpenTechnical: () -> Unit,
    onWatchForgotten: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        WatchSettingsScreen(
            viewModel = hiltViewModel(),
            onNotificationRulesClick = onOpenNotificationApps,
            onFirmwareClick = onOpenFirmwareUpdate,
            onCalibrateHandsClick = onOpenHandsCalibration,
        )
    }
}
