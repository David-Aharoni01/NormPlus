package com.normplus.ui.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.normplus.ble.BleService
import com.normplus.notification.NotificationForwarder
import com.normplus.status.Fix
import com.normplus.status.WatchStatusSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private const val TAG = "StatusFixes"

/** How a composable reaches the app's [WatchStatusSource] without a ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchStatusEntryPoint {
    fun watchStatusSource(): WatchStatusSource
}

/** The app's one [WatchStatusSource], for composables that have no ViewModel of their own. */
@Composable
fun rememberWatchStatusSource(): WatchStatusSource {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, WatchStatusEntryPoint::class.java).watchStatusSource() }
}

/**
 * Carries out a [Fix]: the one tap behind every blocker, in the banner and on any fix-it card
 * (#97). Remember it once per screen and call it with the blocker's `fix`. Each fix opens
 * Android's own dialog or settings page for the thing, and the status is sampled again when
 * the person comes back. Nothing here throws: a settings page this phone lacks falls back to
 * the next one, and the last failure is only logged.
 *
 * Asking to be exempt from battery optimisation is what an always-on companion for a connected
 * device needs (the exemption also lets the service restart itself from the background), and the
 * person is asked, never opted in. Hence BatteryLife is suppressed here; the request is the same
 * one the Connection health card has always made.
 */
@SuppressLint("BatteryLife")
@Composable
fun rememberStatusFixes(): (Fix) -> Unit {
    val context = LocalContext.current
    val source = rememberWatchStatusSource()
    val activity = remember(context) { context.findActivity() }

    val systemScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        source.refresh()
    }
    // Where to send the person when Android will not ask again (a refusal the dialog no longer
    // offers to undo): the settings page where it can be allowed by hand.
    val afterRefusal = remember { arrayOfNulls<Intent>(1) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        source.refresh()
        val fallback = afterRefusal[0].also { afterRefusal[0] = null }
        val refused = results.filterValues { granted -> !granted }.keys
        val willNotAsk = activity != null && refused.isNotEmpty() &&
            refused.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (willNotAsk && fallback != null) systemScreen.launchFirst(listOf(fallback))
    }

    return remember(context, source, activity) {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        fun ask(wanted: List<String>, fallback: Intent) {
            val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                source.refresh()
                return
            }
            afterRefusal[0] = fallback
            runCatching { permissions.launch(missing.toTypedArray()) }
                .onFailure { Log.w(TAG, "permission request failed: ${it.message}"); systemScreen.launchFirst(listOf(fallback)) }
        }

        val fixes: (Fix) -> Unit = { fix ->
            when (fix) {
                Fix.AllowBluetooth -> ask(WatchStatusSource.BLUETOOTH_PERMISSIONS, appDetails)
                Fix.StartService, Fix.Connect -> {
                    BleService.start(context)
                    source.refresh()
                }
                Fix.TurnOnBluetooth -> systemScreen.launchFirst(
                    listOf(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
                )
                Fix.AllowBackground -> systemScreen.launchFirst(listOf(appDetails))
                Fix.IgnoreBatteryOptimisation -> systemScreen.launchFirst(
                    listOf(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    ),
                )
                Fix.AllowNotifications -> {
                    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (canAsk) ask(WatchStatusSource.NOTIFICATION_PERMISSIONS, notificationSettings)
                    else systemScreen.launchFirst(listOf(notificationSettings, appDetails))
                }
                Fix.OpenNotificationAccess -> systemScreen.launchFirst(
                    listOf(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(context, NotificationForwarder::class.java).flattenToString(),
                        ),
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    ),
                )
                Fix.AllowCalls -> ask(WatchStatusSource.CALL_PERMISSIONS, appDetails)
                Fix.OpenBluetoothSettings -> systemScreen.launchFirst(listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
            }
        }
        fixes
    }
}

/** Opens the first of [intents] this phone can open. */
private fun ActivityResultLauncher<Intent>.launchFirst(intents: List<Intent>) {
    for (intent in intents) {
        if (runCatching { launch(intent) }.onFailure { Log.w(TAG, "${intent.action}: ${it.message}") }.isSuccess) return
    }
}

/** The activity behind a composable's context. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
