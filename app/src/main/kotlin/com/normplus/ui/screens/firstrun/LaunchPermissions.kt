package com.normplus.ui.screens.firstrun

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import com.normplus.status.WatchStatusSource

/**
 * The permission request MainActivity makes at launch, moved here from MainActivity (#97) so the
 * first run (#98) can replace it without touching the activity: #98 asks for each permission at
 * the step that needs it, and turns [requestMissing] into a no-op (or deletes this and the
 * activity's two calls with it, in the same change).
 *
 * Today it asks for everything still missing at once, as before: Nearby devices (the only one
 * required), the call permissions (optional: their denial disables only calls) and, on Android
 * 13+, posting notifications.
 *
 * Construct it as a property of the activity: the launcher must be registered before the
 * activity starts.
 *
 * @param onBluetoothGranted runs when the request comes back with Nearby devices granted; the
 *   activity starts the connection service there.
 */
class LaunchPermissions(
    private val activity: ComponentActivity,
    onBluetoothGranted: () -> Unit,
) {
    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.BLUETOOTH_CONNECT] == true) onBluetoothGranted()
        }

    private val wanted: List<String> =
        WatchStatusSource.BLUETOOTH_PERMISSIONS + WatchStatusSource.CALL_PERMISSIONS + WatchStatusSource.NOTIFICATION_PERMISSIONS

    /** Asks for whatever is still missing; nothing when everything is granted. */
    fun requestMissing() {
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) runCatching { launcher.launch(missing.toTypedArray()) }
    }
}
