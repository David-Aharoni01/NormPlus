package com.normplus.ble

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.normplus.data.preferences.WatchPreferences
import com.normplus.notification.NotificationListenerHealth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "ConnWatchdog"

/**
 * Periodic safety net that survives what [BleService] cannot: an OEM battery optimiser killing the
 * process outright, a swallowed `START_STICKY` restart, or a connect cycle that silently ended in
 * `Disconnected` with the re-kick job lost along with the process.
 *
 * Implemented with `AlarmManager`, not `WorkManager`, deliberately: WorkManager is not a dependency
 * of `:app` and pulling it (plus `hilt-work`) in for one 15-minute poll is a lot of surface for no
 * gain — an inexact `setAndAllowWhileIdle` alarm has the same ~15 min practical floor and fires in
 * Doze. Alarms do NOT survive a reboot, which is fine: [BootReceiver] starts the service, and the
 * service reschedules the alarm in `onCreate`.
 *
 * It never opens a connection itself — it only asks [BleManager] (whose `connect()` is mutex- and
 * rate-limit-guarded) or restarts [BleService], so it cannot create a second concurrent connect
 * cycle alongside the service's own re-kick / warm-reconnect paths.
 */
object ConnectionWatchdog {

    /** 15 min — the WorkManager periodic floor, and a comfortable fit for `setAndAllowWhileIdle`
     *  (which the system throttles to roughly once every 9 min while in Doze). */
    const val INTERVAL_MS = 15 * 60 * 1000L

    private const val REQUEST_CODE = 42

    /** Schedule (or re-schedule) the next watchdog tick. Idempotent — the PendingIntent is a
     *  singleton, so re-scheduling replaces the pending alarm rather than stacking alarms. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        // Inexact + allow-while-idle: reliable enough for a safety net, and needs no
        // SCHEDULE_EXACT_ALARM (which is reserved for genuine alarm-clock use cases).
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent(context))
        }.onFailure { Log.w(TAG, "schedule failed: ${it.message}") }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context)) }
            .onFailure { Log.w(TAG, "cancel failed: ${it.message}") }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ConnectionWatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Alarm target for [ConnectionWatchdog]. Verifies the service is running and the link is up, and
 * heals whichever part is missing. Always reschedules itself first so a single failure (or an
 * early `return`) can never end the watchdog chain.
 */
@AndroidEntryPoint
class ConnectionWatchdogReceiver : BroadcastReceiver() {

    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var bleManager: BleManager

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Chain the next tick before anything that can bail out.
        ConnectionWatchdog.schedule(app)

        val pending = goAsync()
        // Detached scope: a BroadcastReceiver has no scope of its own, and goAsync() keeps the
        // process alive until finish(). SupervisorJob + the try/finally guarantee finish() runs.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(WORK_TIMEOUT_MS) { check(app) }
                    ?: Log.w(TAG, "watchdog check timed out")
            } catch (t: Throwable) {
                // A watchdog that crashes the process is worse than no watchdog.
                Log.e(TAG, "watchdog check failed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun check(app: Context) {
        // Independent of the BLE link: the notification listener can be unbound while the watch is
        // perfectly connected, which looks like "notifications randomly stopped working". Runs
        // before the auto-start bail-out because it is not part of the connection lifecycle.
        NotificationListenerHealth.requestRebindIfNeeded(app)

        if (!watchPreferences.isAutoStartEnabled()) {
            Log.i(TAG, "tick: auto-start disabled by the user — standing down")
            return
        }
        val mac = watchPreferences.getDeviceMac()
        if (mac == null) {
            Log.i(TAG, "tick: no paired watch — nothing to do")
            return
        }
        if (!BleService.hasBleConnectPermission(app)) {
            Log.w(TAG, "tick: BLUETOOTH_CONNECT not granted — cannot reconnect")
            return
        }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.state != BluetoothAdapter.STATE_ON) {
            Log.i(TAG, "tick: Bluetooth adapter off — waiting for ACTION_STATE_CHANGED")
            return
        }

        val serviceRunning = BleService.isRunning.value
        val connected = bleManager.isConnected
        Log.i(TAG, "tick: serviceRunning=$serviceRunning connected=$connected")
        if (serviceRunning && connected) return

        if (serviceRunning) {
            // Process alive, service up, link down: go straight to the singleton BleManager. Its
            // connect() is mutex-guarded and rate-limited, so this cannot race the service's own
            // re-kick or a warm reconnect already in flight — the loser is simply skipped.
            bleManager.connect(mac)
        } else {
            // Process/service was killed. This is a background foreground-service start, which
            // Android 12+ rejects unless the app is exempt from battery optimisations — hence the
            // in-app exemption prompt. BleService.resume() swallows the denial and logs it.
            BleService.resume(app)
        }
    }

    private companion object {
        const val WORK_TIMEOUT_MS = 20_000L
    }
}
