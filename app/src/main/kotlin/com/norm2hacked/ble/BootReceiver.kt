package com.norm2hacked.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.norm2hacked.data.preferences.WatchPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "BootReceiver"

/**
 * Starts [BleService] again after the events that would otherwise leave the watch silently
 * disconnected until the user next opens the app:
 *
 *  - `BOOT_COMPLETED` — the phone rebooted.
 *  - `LOCKED_BOOT_COMPLETED` — direct-boot phase, before the user's first unlock (see below).
 *  - `MY_PACKAGE_REPLACED` — an app update stops our service and it is not restarted for us.
 *
 * **Direct boot.** The receiver is `directBootAware`, so `LOCKED_BOOT_COMPLETED` reaches us — but
 * our DataStore lives in *credential*-protected storage and the saved MAC is unreadable until the
 * user unlocks (BLE bonding keys are likewise unavailable). So while the user is still locked we
 * log and return; `BOOT_COMPLETED`, which is delivered right after the unlock, does the real work.
 * Handling the action is still worthwhile: it is the earliest point at which we know a boot
 * happened, and it makes the locked-vs-unlocked split explicit rather than accidental.
 *
 * **API 35 background-start rules.** A `BOOT_COMPLETED` receiver is one of the documented
 * exemptions from the Android 12+ background foreground-service-start restriction, and
 * `connectedDevice` is a permitted boot-time FGS type (unlike e.g. `camera`/`microphone`, which are
 * banned at boot since Android 14). The start still requires `BLUETOOTH_CONNECT` to be granted —
 * `startForeground` with the `connectedDevice` type throws without it — so we check first and skip
 * (loudly) rather than crash. [BleService.resume] additionally wraps the start in `runCatching`, so
 * even an unexpected `ForegroundServiceStartNotAllowedException` degrades to a log line.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var watchPreferences: WatchPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED_ACTIONS) {
            Log.d(TAG, "ignoring action=$action")
            return
        }
        val app = context.applicationContext

        // Direct boot: credential-protected storage (our DataStore) is not readable yet.
        val userUnlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!userUnlocked) {
            Log.i(TAG, "$action received before first unlock — deferring to BOOT_COMPLETED")
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(PREFS_TIMEOUT_MS) { autoStart(app, action) }
                    ?: Log.w(TAG, "$action: timed out reading preferences — not starting")
            } catch (t: Throwable) {
                Log.e(TAG, "$action handling failed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun autoStart(app: Context, action: String?) {
        val mac = watchPreferences.getDeviceMac()
        if (mac == null) {
            Log.i(TAG, "$action: no saved device MAC (watch not paired yet) — not starting the service")
            return
        }
        if (!watchPreferences.isAutoStartEnabled()) {
            Log.i(TAG, "$action: auto-start disabled by the user (Disconnect) — not starting")
            return
        }
        if (!BleService.hasBleConnectPermission(app)) {
            Log.w(TAG, "$action: BLUETOOTH_CONNECT not granted — cannot start a connectedDevice " +
                    "foreground service; the user must reopen the app and grant it")
            return
        }
        Log.i(TAG, "$action: starting BleService for saved device")
        // ACTION_RESUME (not START): an unattended start must respect the stored auto-start flag
        // instead of re-arming it the way an explicit user launch does.
        BleService.resume(app)
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        const val PREFS_TIMEOUT_MS = 5_000L
    }
}
