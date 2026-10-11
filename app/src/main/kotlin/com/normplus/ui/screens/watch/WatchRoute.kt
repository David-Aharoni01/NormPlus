package com.normplus.ui.screens.watch

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.ui.components.rememberWatchTime
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.rememberStatusFixes
import com.normplus.ui.shell.rememberWatchStatusSource

/**
 * Watch (#102), the third tab: the watch drawn live, its card, the fix-its, the settings, each
 * sent as it changes, and Forget this watch. The shell draws its header (the large "Watch"
 * title, the status pill or the banner) and the floating navigation capsule; this fills the
 * screen and scrolls under both.
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
    val viewModel: WatchViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = LocalWatchStatus.current
    val source = rememberWatchStatusSource()
    val fix = rememberStatusFixes()
    val now = rememberWatchTime()
    val actions = remember(viewModel, source, fix) {
        WatchActions(
            onFix = fix,
            onDismissBatteryPrompt = source::dismissBatteryOptimisation,
            onFindWatch = viewModel::findWatch,
            onBrightness = viewModel::setBrightness,
            onTimeout = viewModel::setTimeout,
            onDnd = viewModel::setDnd,
            onVibration = viewModel::setVibration,
            onPowerSave = viewModel::setPowerSave,
            onMetric = viewModel::setMetric,
            onBit = viewModel::setBit,
            onStepGoal = viewModel::setStepGoal,
            onDeleteAfterSync = viewModel::setDeleteAfterSync,
            onRetry = viewModel::retry,
            onDismissSend = viewModel::dismiss,
            onOpenNotificationApps = onOpenNotificationApps,
            onOpenWatchScreens = onOpenWatchScreens,
            onOpenHandsCalibration = onOpenHandsCalibration,
            onOpenFirmwareUpdate = onOpenFirmwareUpdate,
            onOpenTechnical = onOpenTechnical,
            onForget = { viewModel.forgetWatch(onWatchForgotten) },
        )
    }
    WatchContent(
        state = state,
        status = status,
        time = now.toLocalTime(),
        date = now.toLocalDate(),
        contentPadding = contentPadding,
        actions = actions,
    )
}
