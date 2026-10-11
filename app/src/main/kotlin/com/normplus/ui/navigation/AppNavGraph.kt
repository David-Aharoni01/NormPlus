package com.normplus.ui.navigation

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.components.ShellStatus
import com.normplus.ui.screens.calibration.HandsCalibrationRoute
import com.normplus.ui.screens.daydetail.DayDetailRoute
import com.normplus.ui.screens.firmware.FirmwareRoute
import com.normplus.ui.screens.firstrun.FirstRunRoute
import com.normplus.ui.screens.notificationapps.NotificationAppsRoute
import com.normplus.ui.screens.technical.TechnicalRoute
import com.normplus.ui.screens.watchscreens.WatchScreensRoute
import com.normplus.ui.shell.TabsHost
import com.normplus.ui.theme.NormMotion
import com.normplus.ui.theme.NormPlusTheme

/**
 * The app's graph (#97): the first run, the tabs (their own graph, in [TabsHost]), and every
 * screen below a tab, each drawn full screen over the tabs. The shell owns this file; a screen
 * issue changes only its own entry composable (docs/app.md, "The navigation contract").
 *
 * Motion: going to a screen is a cut; going back is Material's predictive back preview
 * ([NormMotion.backPreviewExit]), which the back gesture draws as it goes. A cut throughout
 * with animations removed.
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: Destination,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val removed = NormPlusTheme.animationsRemoved
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { NormMotion.backPreviewExit(removed) },
    ) {
        composable<Destination.FirstRun> {
            // The connection is the first run's own business: no pill or banner over it.
            CompositionLocalProvider(LocalShellStatus provides ShellStatus()) {
                FirstRunRoute(
                    onFinished = {
                        navController.navigate(Destination.Tabs) {
                            popUpTo<Destination.FirstRun> { inclusive = true }
                        }
                    },
                    onOpenNotificationApps = { navController.navigate(Destination.NotificationApps) { launchSingleTop = true } },
                )
            }
        }
        composable<Destination.Tabs> { entry ->
            TabsHost(
                navigate = { destination ->
                    if (entry.isCurrent()) navController.navigate(destination) { launchSingleTop = true }
                },
                onWatchForgotten = {
                    navController.navigate(Destination.FirstRun) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onSync = onSync,
            )
        }
        composable<Destination.DayDetail> { entry ->
            DayDetailRoute(date = entry.toRoute<Destination.DayDetail>().date, onBack = navController.backFrom(entry))
        }
        composable<Destination.WatchScreens> { entry ->
            WatchScreensRoute(onBack = navController.backFrom(entry))
        }
        composable<Destination.NotificationApps> { entry ->
            NotificationAppsRoute(onBack = navController.backFrom(entry))
        }
        composable<Destination.HandsCalibration> { entry ->
            HandsCalibrationRoute(onClose = navController.backFrom(entry))
        }
        composable<Destination.FirmwareUpdate> { entry ->
            FirmwareRoute(
                customFile = entry.toRoute<Destination.FirmwareUpdate>().customFile?.let(Uri::parse),
                onClose = navController.backFrom(entry),
            )
        }
        composable<Destination.Technical> { entry ->
            TechnicalRoute(
                onBack = navController.backFrom(entry),
                onUpdateFromFile = { uri ->
                    if (entry.isCurrent()) navController.navigate(Destination.FirmwareUpdate(uri.toString()))
                },
            )
        }
    }
}

/** Whether [this] is the screen on top, settled: a second tap during a transition does nothing. */
private fun NavBackStackEntry.isCurrent(): Boolean = lifecycle.currentState == Lifecycle.State.RESUMED

/** Back from [entry]'s screen, once: a double tap cannot pop the screen beneath it as well. */
private fun NavHostController.backFrom(entry: NavBackStackEntry): () -> Unit = {
    if (entry.isCurrent()) popBackStack()
}
