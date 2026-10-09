@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui

import com.normplus.ui.legacy.LegacyMuted
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.normplus.ble.BleService
import com.normplus.data.preferences.WatchPreferences
import com.normplus.ui.navigation.AppNavGraph
import com.normplus.ui.navigation.Screen
import com.normplus.ui.legacy.Background
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.legacy.Surface
import com.normplus.ui.legacy.Teal
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var watchPreferences: WatchPreferences

    private val requiredPermissions: Array<String> get() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            // Phone-call support (non-fatal if denied — calls just won't forward / can't be
            // answered from the watch). BLE startup below is gated only on BLUETOOTH_CONNECT.
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Start service as long as the critical BLE connect permission was granted.
        // POST_NOTIFICATIONS denial is not fatal — the notification just won't show.
        if (results[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            BleService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startBleServiceIfPermitted()

        setContent {
            NormPlusTheme {
                // Determine start destination asynchronously — never block the main thread.
                var startDestination by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val hasMac = watchPreferences.getDeviceMac() != null
                    startDestination = if (hasMac) Screen.Dashboard.route else Screen.Pairing.route
                }
                if (startDestination != null) {
                    MainScaffold(startDestination = startDestination!!)
                } else {
                    // Brief dark splash while DataStore reads
                    Box(Modifier.fillMaxSize().background(Background))
                }
            }
        }
    }

    private fun startBleServiceIfPermitted() {
        // BLE connect is the only permission the service truly needs; the call permissions are
        // optional (their denial just disables call forwarding). So start as soon as BLE is
        // granted, and separately request whatever is still missing (incl. the optional perms).
        val bleGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (bleGranted) BleService.start(this)

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())
    }
}

@Composable
private fun MainScaffold(startDestination: String) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavRoutes = listOf(Screen.Dashboard.route, Screen.Activity.route, Screen.Settings.route)
    val showBottomBar = currentRoute in bottomNavRoutes

    Scaffold(
        containerColor = Background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Surface) {
                    NavigationBarItem(
                        selected = currentRoute == Screen.Dashboard.route,
                        onClick = { navController.navigate(Screen.Dashboard.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Dashboard") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Teal,
                            selectedTextColor = Teal,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = LegacyMuted,
                            unselectedTextColor = LegacyMuted,
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Activity.route,
                        onClick = { navController.navigate(Screen.Activity.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = "Activity") },
                        label = { Text("Activity") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Teal,
                            selectedTextColor = Teal,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = LegacyMuted,
                            unselectedTextColor = LegacyMuted,
                        )
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Settings.route,
                        onClick = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Teal,
                            selectedTextColor = Teal,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = LegacyMuted,
                            unselectedTextColor = LegacyMuted,
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        AppNavGraph(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
