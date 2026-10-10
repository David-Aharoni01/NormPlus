package com.normplus.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.normplus.ble.BleService
import com.normplus.data.preferences.WatchPreferences
import com.normplus.ui.screens.firstrun.LaunchPermissions
import com.normplus.ui.shell.NormPlusApp
import com.normplus.ui.theme.NormPlusTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The one activity (#97): edge-to-edge, the connection service started on opening, and
 * [NormPlusApp]. The first run owns the permission request ([LaunchPermissions], #98).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var watchPreferences: WatchPreferences

    // Registered as a property: a launcher must exist before the activity starts.
    private val launchPermissions = LaunchPermissions(this) { BleService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge, both bars transparent over the ground. The ground follows the system's
        // dark setting, as NormPlusTheme does, so the bars' icons do too: light on the dark
        // ground, dark on the light one (a change of setting recreates the activity).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Opening the app is the explicit intent that (re)starts the always-on connection, as it
        // always was; Nearby devices is the only permission the service needs. A configuration
        // change (rotation, dark mode) is not an opening: it neither restarts a service stopped
        // from its notification nor asks for permissions again.
        if (savedInstanceState == null) {
            if (BleService.hasBleConnectPermission(this)) BleService.start(this)
            launchPermissions.requestMissing()
        }

        setContent {
            NormPlusTheme {
                // Where to start depends on whether a watch is saved: read once, off the main thread.
                var startsWithWatch by rememberSaveable { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    if (startsWithWatch == null) startsWithWatch = watchPreferences.getDeviceMac() != null
                }
                when (val withWatch = startsWithWatch) {
                    null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    else -> NormPlusApp(startsWithWatch = withWatch)
                }
            }
        }
    }
}
