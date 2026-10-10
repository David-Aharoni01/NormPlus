package com.normplus.ui.screens.firstrun

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.ui.screens.pairing.PairingScreen

/**
 * The first run (#98): the start destination while no watch is saved, and where "Forget this
 * watch" lands. The shell shows no banner here: the connection is this flow's own business.
 *
 * @param onFinished the watch is saved and bound: the shell goes to Today and drops the first
 *   run from the back stack.
 * @param onOpenNotificationApps the "choose apps" step can open Notification apps; Back returns
 *   here.
 *
 * Stub (#97): the old pairing screen.
 */
@Composable
fun FirstRunRoute(
    onFinished: () -> Unit,
    onOpenNotificationApps: () -> Unit,
) {
    PairingScreen(viewModel = hiltViewModel(), onConnected = onFinished)
}
