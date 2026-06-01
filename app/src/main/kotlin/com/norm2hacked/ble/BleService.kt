package com.norm2hacked.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.norm2hacked.R
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "BleService"
private const val CHANNEL_ID = "ble_service"
private const val NOTIF_ID = 1

// After BleManager exhausts its reconnect budget and gives up (state → Disconnected), the service
// stays alive and re-kicks a fresh connect cycle after this delay, so the watch re-attaches on its
// own when it returns to range without the user reopening the app. This is what makes the service
// genuinely "always-on". Cancelled the moment the connection leaves Disconnected.
private const val REKICK_DELAY_MS = 30_000L

// Lightweight keep-alive cadence while the link is Ready. The Norm 2's bonded link normally stays
// up on its own via BLE connection supervision, so this is a conservative safety net — a periodic
// remote-RSSI read (see BleManager.readRemoteRssi) that exercises the radio without touching the
// command queue or expecting a protocol reply, so it can never fail the connection.
//
// Set <= 0 to disable. PENDING ON-DEVICE VERIFICATION (see CLAUDE.md warm-connection plan): if the
// watch is observed to drop genuinely idle links despite this, raise the cadence or switch to a
// periodic battery CHECK; if the link never drops idle, this can be disabled to save a little power.
private const val KEEPALIVE_INTERVAL_MS = 90_000L

/**
 * Always-on foreground service that owns [BleManager]'s GATT link for the lifetime of the app
 * (and in the background). Holding the link alive is the key to fast reconnects: a brief drop
 * reconnects on a still-warm SMP/encryption state (BleManager's warm-reconnect path) instead of
 * paying the ~8s cold-connect SMP race. See "Warm-connection via BleService" in CLAUDE.md.
 *
 * Responsibilities:
 *  - Run as a typed (`connectedDevice`) foreground service so the OS keeps the process alive.
 *  - Keep the link warm: a conservative RSSI keep-alive while Ready (see [KEEPALIVE_INTERVAL_MS]).
 *  - Stay always-on: re-kick a connect after BleManager gives up, until the user stops the service.
 *  - Expose a "Disconnect" notification action ([ACTION_STOP]) for a clean, user-initiated stop.
 *
 * BleManager owns the fast retry/warm-reconnect logic on a live drop; this service only re-kicks
 * once that gives up, and never fights it.
 */
@AndroidEntryPoint
class BleService : Service() {

    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var watchPreferences: WatchPreferences

    private val serviceScope = CoroutineScope(SupervisorJob())

    // Set when the user explicitly stops the service (ACTION_STOP). Suppresses the always-on
    // re-kick so a deliberate stop actually stays stopped (until the app is reopened).
    @Volatile private var userStopped = false

    // Pending "re-kick a connect after give-up" job; cancelled whenever we leave Disconnected.
    private var rekickJob: Job? = null
    // Periodic keep-alive job; runs only while Ready.
    private var keepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Searching for watch…"))
        observeConnectionState()
        connectIfKnownDevice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServiceCleanly()
            return START_NOT_STICKY
        }
        // START_STICKY: if the OS kills us under memory pressure, recreate and reconnect.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        rekickJob?.cancel()
        keepAliveJob?.cancel()
        // Only tear the BLE link down on a deliberate stop. On an OS-initiated destroy we leave the
        // singleton BleManager's link intact: if the process survives, the link stays warm; if the
        // process is killed, it's gone anyway and START_STICKY brings us back to reconnect.
        if (userStopped) bleManager.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeConnectionState() {
        bleManager.connectionState.onEach { state ->
            updateNotification(notificationTextFor(state))

            // Keep-alive runs only while the link is up.
            if (state is BleConnectionState.Ready) startKeepAlive() else stopKeepAlive()

            // Always-on re-kick: BleManager gave up (Disconnected) but the user didn't stop us.
            // Schedule a delayed fresh connect; cancel any pending one whenever we leave Disconnected.
            if (state is BleConnectionState.Disconnected && !userStopped) {
                scheduleRekick()
            } else {
                rekickJob?.cancel(); rekickJob = null
            }
        }.launchIn(serviceScope)
    }

    private fun connectIfKnownDevice() {
        serviceScope.launch {
            val mac = watchPreferences.getDeviceMac() ?: return@launch
            bleManager.connect(mac)
        }
    }

    /** Re-establish the connection a while after BleManager gave up, so the watch re-attaches on its own. */
    private fun scheduleRekick() {
        if (rekickJob?.isActive == true) return
        rekickJob = serviceScope.launch {
            val mac = watchPreferences.getDeviceMac() ?: return@launch
            Log.i(TAG, "Connection idle — re-kicking connect to $mac in ${REKICK_DELAY_MS}ms")
            delay(REKICK_DELAY_MS)
            if (userStopped) return@launch
            // Run connect() in a detached child so the observer cancelling rekickJob (when state
            // moves to Connecting) can't cancel the connect mid-flight.
            serviceScope.launch { bleManager.connect(mac) }
        }
    }

    private fun startKeepAlive() {
        if (KEEPALIVE_INTERVAL_MS <= 0L) return
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = serviceScope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (bleManager.isConnected) bleManager.readRemoteRssi()
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel(); keepAliveJob = null
    }

    private fun stopServiceCleanly() {
        Log.i(TAG, "ACTION_STOP — user-initiated stop")
        userStopped = true
        rekickJob?.cancel()
        keepAliveJob?.cancel()
        bleManager.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        // Android 14 (and the connectedDevice type since Android 10) requires the foreground
        // service type to be specified at startForeground() time, backed by the manifest
        // declaration + FOREGROUND_SERVICE_CONNECTED_DEVICE permission. ServiceCompat handles the
        // per-API plumbing. Guarded so a background-start denial (Android 12+) can't crash us.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "startForeground failed: ${it.message}") }
    }

    private fun notificationTextFor(state: BleConnectionState): String = when (state) {
        is BleConnectionState.Ready -> "Connected to ${state.deviceName}"
        is BleConnectionState.Connecting -> "Connecting…"
        is BleConnectionState.Discovering -> "Setting up…"
        is BleConnectionState.Scanning -> "Scanning…"
        // After a few failed cold attempts, hint at the only reliable cure (a BT adapter reset).
        is BleConnectionState.Error ->
            if (state.retryCount >= 3) "Trouble connecting — try toggling Bluetooth" else "Reconnecting…"
        is BleConnectionState.Disconnected -> "Watch disconnected"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watch Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, BleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Norm 2")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentIntent(openPi)
            .addAction(0, "Disconnect", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        /** User-initiated clean stop (fired by the notification's Disconnect action): tears down
         *  the link and stops the service without scheduling the always-on re-kick. */
        const val ACTION_STOP = "com.norm2hacked.ble.action.STOP"

        /** Start (or no-op if already running) the always-on connection service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BleService::class.java))
        }
    }
}
