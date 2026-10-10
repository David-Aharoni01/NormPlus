package com.normplus.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.navigation.AppNavGraph
import com.normplus.ui.navigation.Destination

/**
 * Norm+ itself, inside MainActivity's theme (#97): the app's graph, started at the first run
 * when no watch is saved and at Today otherwise, with the watch's status provided to every
 * screen below it: [LocalWatchStatus] for screens that need the facts, and [LocalShellStatus]
 * (the pill and the banner, worded and with their fixes) for every header.
 *
 * Coming back to the front samples the system again (permissions, Bluetooth, battery and
 * background settings), since none of it can be observed while Norm+ is away.
 */
@Composable
fun NormPlusApp(startsWithWatch: Boolean, viewModel: ShellViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val fixes = rememberStatusFixes()
    LifecycleResumeEffect(viewModel) {
        viewModel.onAppVisible()
        onPauseOrDispose { }
    }

    val navController = rememberNavController()
    val start = remember { if (startsWithWatch) Destination.Tabs else Destination.FirstRun }
    CompositionLocalProvider(
        LocalWatchStatus provides status,
        LocalShellStatus provides shellStatus(status, fixes),
    ) {
        AppNavGraph(navController = navController, startDestination = start, onSync = viewModel::sync)
    }
}
