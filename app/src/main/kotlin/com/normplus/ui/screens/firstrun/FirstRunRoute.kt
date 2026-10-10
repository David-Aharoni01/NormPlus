package com.normplus.ui.screens.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.normplus.status.Fix
import com.normplus.ui.components.rememberWatchTime
import com.normplus.ui.shell.findActivity
import com.normplus.ui.shell.rememberStatusFixes

/**
 * The first run (#98): the start destination while no watch is saved, and where "Forget this
 * watch" lands. The shell shows no banner here: the connection is this flow's own business.
 *
 * Welcome, Nearby devices, Find your watch, Connecting (with Android's pairing request), the
 * watch's bind, then notifications, calls and the battery, each permission asked at its step;
 * at the end Today, with the first sync started. Only Nearby devices is required; anything
 * skipped is a fix-it on Watch afterwards.
 *
 * Close leaves the app until the watch is set up (Back from the first step does the same);
 * after that it skips the remaining steps and goes to Today.
 *
 * @param onFinished the watch is saved and bound: the shell goes to Today and drops the first
 *   run from the back stack.
 * @param onOpenNotificationApps the "choose apps" step can open Notification apps; Back returns
 *   here.
 */
@Composable
fun FirstRunRoute(
    onFinished: () -> Unit,
    onOpenNotificationApps: () -> Unit,
    viewModel: FirstRunViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fixes = rememberStatusFixes()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val time = rememberWatchTime().toLocalTime()

    LaunchedEffect(state.finished) { if (state.finished) onFinished() }

    // Back walks back through the steps where that makes sense; elsewhere it is the system's.
    BackHandler(enabled = previousPlace(Place(state.step, state.notificationPart), state.grants) != null) {
        viewModel.back()
    }

    val actions = remember(viewModel, fixes, activity) {
        FirstRunActions(
            onClose = {
                if (viewModel.state.value.step.number > FirstRunStep.Bind.number) viewModel.finish() else activity?.finish()
            },
            onNext = viewModel::next,
            onAsk = { ask ->
                viewModel.asked(ask)
                fixes(
                    when (ask) {
                        Ask.Bluetooth -> Fix.AllowBluetooth
                        Ask.Notifications -> Fix.AllowNotifications
                        Ask.NotificationAccess -> Fix.OpenNotificationAccess
                        Ask.Calls -> Fix.AllowCalls
                        Ask.Battery -> Fix.IgnoreBatteryOptimisation
                    },
                )
            },
            onNotNowBattery = viewModel::notNowBattery,
            onTurnOnBluetooth = { fixes(Fix.TurnOnBluetooth) },
            onScan = viewModel::startScan,
            onConnectTo = viewModel::connectTo,
            onAddressChange = viewModel::onAddressChange,
            onConnectByAddress = viewModel::connectByAddress,
            onRetryConnect = viewModel::retryConnect,
            onChooseAnotherWatch = viewModel::chooseAnotherWatch,
            onRetryBind = viewModel::bind,
            onContinueWithoutBind = viewModel::continueWithoutBind,
            onOpenNotificationApps = {
                viewModel.notificationAppsOpened()
                onOpenNotificationApps()
            },
        )
    }

    FirstRunContent(state = state, time = time, actions = actions)
}
