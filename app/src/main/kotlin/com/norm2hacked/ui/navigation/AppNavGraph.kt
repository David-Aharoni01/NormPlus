package com.norm2hacked.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.norm2hacked.ui.screens.activity.ActivityHistoryScreen
import com.norm2hacked.ui.screens.activity.WorkoutDetailScreen
import com.norm2hacked.ui.screens.dashboard.DashboardScreen
import com.norm2hacked.ui.screens.firmware.FirmwareScreen
import com.norm2hacked.ui.screens.pairing.PairingScreen
import com.norm2hacked.ui.screens.settings.NotificationRulesScreen
import com.norm2hacked.ui.screens.settings.WatchSettingsScreen

sealed class Screen(val route: String) {
    data object Pairing : Screen("pairing")
    data object Dashboard : Screen("dashboard")
    data object Activity : Screen("activity")
    data object WorkoutDetail : Screen("workout/{id}") {
        fun route(id: Long) = "workout/$id"
    }
    data object Settings : Screen("settings")
    data object NotificationRules : Screen("notification_rules")
    data object Firmware : Screen("firmware")
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Screen.Pairing.route) {
            PairingScreen(
                viewModel = hiltViewModel(),
                onConnected = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Pairing.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.Dashboard.route) {
            DashboardScreen(viewModel = hiltViewModel())
        }
        composable(Screen.Activity.route) {
            ActivityHistoryScreen(
                viewModel = hiltViewModel(),
                onWorkoutClick = { id -> navController.navigate(Screen.WorkoutDetail.route(id)) }
            )
        }
        composable(
            route = Screen.WorkoutDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { back ->
            WorkoutDetailScreen(
                viewModel = hiltViewModel(),
                workoutId = back.arguments?.getLong("id") ?: return@composable,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            WatchSettingsScreen(
                viewModel = hiltViewModel(),
                onNotificationRulesClick = { navController.navigate(Screen.NotificationRules.route) },
                onFirmwareClick = { navController.navigate(Screen.Firmware.route) }
            )
        }
        composable(Screen.NotificationRules.route) {
            NotificationRulesScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Firmware.route) {
            FirmwareScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
