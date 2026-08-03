package com.norm2hacked.notification

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat

private const val TAG = "NotifListenerHealth"

/**
 * Keeps [NotificationForwarder] actually *bound*.
 *
 * A `NotificationListenerService` is owned by the system, not by us: nothing in our own uptime
 * machinery ([com.norm2hacked.ble.BleService], the boot receiver, the watchdog) restarts it. When an
 * OEM battery optimiser kills our process, the system is supposed to rebind the listener — but in
 * practice it often does not until the next reboot or a permission toggle. The symptom is the worst
 * kind: the watch stays happily *connected* while notifications silently stop arriving, so nothing
 * in the connection UI looks wrong.
 *
 * [NotificationListenerService.requestRebind] is the supported fix. It is a no-op unless the user
 * has granted listener access, so it is safe to call speculatively — we drive it from the watchdog
 * tick alongside the BLE check.
 */
object NotificationListenerHealth {

    /** True when the user has granted notification-listener access to our package. */
    fun isPermissionGranted(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /**
     * Asks the system to rebind the forwarder if we hold the permission but the service is not
     * currently connected.
     *
     * Never throws: [isConnected] is best-effort (the service instance only exists while bound) and
     * a `requestRebind` on a ROM that mishandles it must not take down the watchdog.
     */
    fun requestRebindIfNeeded(ctx: Context) {
        if (!isPermissionGranted(ctx)) {
            Log.d(TAG, "listener access not granted — nothing to rebind")
            return
        }
        if (NotificationForwarder.isConnected) return

        val component = ComponentName(ctx, NotificationForwarder::class.java)
        runCatching { NotificationListenerService.requestRebind(component) }
            .onSuccess { Log.i(TAG, "listener not bound — requested rebind") }
            .onFailure { Log.w(TAG, "requestRebind failed: ${it.message}") }
    }
}
