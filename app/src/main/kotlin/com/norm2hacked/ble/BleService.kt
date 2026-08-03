package com.norm2hacked.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.norm2hacked.R
import com.norm2hacked.call.CallManager
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *  - Survive the ways the link dies silently: adapter toggled off/on ([BluetoothStateReceiver]),
 *    process kill (`START_STICKY` + a null-intent resume from prefs), reboot / app update
 *    ([BootReceiver]), and an outright process kill by an OEM optimiser ([ConnectionWatchdog]).
 *  - Report honestly: the ongoing notification names the actual blocker (Bluetooth off, missing
 *    permission, battery restriction) instead of a generic "disconnected".
 *  - Expose a "Disconnect" notification action ([ACTION_STOP]) for a clean, user-initiated stop.
 *
 * **Connect serialisation.** Every path here (start intent, re-kick, adapter-ON, watchdog) funnels
 * into [requestConnect] → `BleManager.connect`, which is mutex-guarded and rate-limited; a losing
 * caller is skipped, never queued. BleManager alone owns retry/warm-reconnect on a live drop; this
 * service only re-kicks once that has given up, and never fights it.
 */
@AndroidEntryPoint
class BleService : Service() {

    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var watchPreferences: WatchPreferences
    @Inject lateinit var callManager: CallManager

    private val serviceScope = CoroutineScope(SupervisorJob())

    // Set when the user explicitly stops the service (ACTION_STOP). Suppresses the always-on
    // re-kick so a deliberate stop actually stays stopped (mirrored into DataStore so boot and the
    // watchdog respect it too, until the user reopens the app).
    @Volatile private var userStopped = false

    // Last known adapter state, kept by BluetoothStateReceiver. While Bluetooth is off there is no
    // point retrying — every GATT call fails instantly, which would burn the reconnect budget and
    // leave us idling on the slow re-kick long after Bluetooth returns.
    @Volatile private var bluetoothOn = true

    // Pending "re-kick a connect after give-up" job; cancelled whenever we leave Disconnected.
    private var rekickJob: Job? = null
    // Periodic keep-alive job; runs only while Ready.
    private var keepAliveJob: Job? = null

    private var btStateReceiver: BluetoothStateReceiver? = null

    // Last state rendered into the notification, so adapter/permission changes can re-render it.
    @Volatile private var lastState: BleConnectionState = BleConnectionState.Disconnected

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bluetoothOn = isBluetoothOn(this)
        startForegroundCompat(buildNotification(notificationTextFor(lastState)))
        _isRunning.value = true
        registerBluetoothStateReceiver()
        observeConnectionState()
        // Safety net for the failure modes this service cannot see (its own process being killed).
        ConnectionWatchdog.schedule(this)
        // Forward phone calls to the watch + handle the watch's answer/reject. Lives for the
        // connected session; sends are no-ops while disconnected (BleManager guards them).
        callManager.start(this, serviceScope)
    }

    /**
     * Intent contract:
     *  - [ACTION_STOP]   — user-initiated stop; disables auto-start until the app is reopened.
     *  - [ACTION_START]  — explicit user intent (app launched): (re-)arms auto-start and connects.
     *  - [ACTION_RESUME] — unattended start (boot, app update, watchdog): connects only if
     *                      auto-start is still enabled.
     *  - `null` intent   — a `START_STICKY` re-creation after a process kill. Android delivers no
     *                      extras here, so it is treated exactly like [ACTION_RESUME]: everything
     *                      needed (MAC, auto-start flag) is re-read from DataStore.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_STOP -> {
                stopServiceCleanly()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                userStopped = false
                serviceScope.launch { watchPreferences.setAutoStartEnabled(true) }
                requestConnect("user-start")
            }
            else -> resumeFromPreferences(action ?: "sticky-restart")
        }
        // START_STICKY: if the OS kills us under memory pressure, recreate and reconnect.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isRunning.value = false
        rekickJob?.cancel()
        keepAliveJob?.cancel()
        btStateReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        btStateReceiver = null
        callManager.stop()
        // Only tear the BLE link down on a deliberate stop. On an OS-initiated destroy we leave the
        // singleton BleManager's link intact: if the process survives, the link stays warm; if the
        // process is killed, it's gone anyway and START_STICKY brings us back to reconnect.
        if (userStopped) bleManager.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Start paths ───────────────────────────────────────────────────────────

    /** Unattended start: honour a previous user "Disconnect" instead of overriding it. */
    private fun resumeFromPreferences(reason: String) {
        serviceScope.launch {
            if (!watchPreferences.isAutoStartEnabled()) {
                Log.i(TAG, "$reason: auto-start disabled by the user — stopping again")
                userStopped = true
                stopServiceCleanly()
                return@launch
            }
            userStopped = false
            requestConnect(reason)
        }
    }

    /**
     * Single funnel for every connect request. Checks the preconditions that would otherwise fail
     * silently (no paired watch, revoked permission, adapter off) and surfaces them in the
     * notification, then delegates to [BleManager.connect] — the only place a connection is opened.
     */
    private fun requestConnect(reason: String) {
        rekickJob?.cancel(); rekickJob = null
        serviceScope.launch {
            val mac = watchPreferences.getDeviceMac()
            if (mac == null) {
                Log.i(TAG, "connect($reason): no saved device MAC — waiting for pairing")
                refreshNotification()
                return@launch
            }
            if (!hasBleConnectPermission(this@BleService)) {
                Log.w(TAG, "connect($reason): BLUETOOTH_CONNECT revoked — cannot connect")
                refreshNotification()
                return@launch
            }
            if (!isBluetoothOn(this@BleService)) {
                bluetoothOn = false
                Log.i(TAG, "connect($reason): Bluetooth is off — waiting for ACTION_STATE_CHANGED")
                refreshNotification()
                return@launch
            }
            bluetoothOn = true
            if (bleManager.isConnected) {
                Log.d(TAG, "connect($reason): already connected")
                return@launch
            }
            Log.i(TAG, "connect($reason): connecting to saved device")
            bleManager.connect(mac)
        }
    }

    // ── Adapter state ─────────────────────────────────────────────────────────

    private fun registerBluetoothStateReceiver() {
        val r = BluetoothStateReceiver { on -> onBluetoothStateChanged(on) }
        btStateReceiver = r
        ContextCompat.registerReceiver(
            this,
            r,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // ACTION_STATE_CHANGED is a protected system broadcast
        )
    }

    private fun onBluetoothStateChanged(on: Boolean) {
        bluetoothOn = on
        if (on) {
            // The adapter comes back with a cold SMP state, so this pays the ~8s cold-connect
            // penalty — but it is the difference between reconnecting in seconds and not at all.
            Log.i(TAG, "Bluetooth ON — reconnecting")
            requestConnect("bluetooth-on")
        } else {
            // Stop cleanly rather than thrashing retries against a dead adapter: cancel the
            // pending re-kick/keep-alive and drop the (already dead) GATT handle. disconnect()
            // clears BleManager's retry counters and warm-reconnect flag, so nothing schedules
            // itself behind our back while Bluetooth is off.
            Log.i(TAG, "Bluetooth OFF — standing down until it returns")
            rekickJob?.cancel(); rekickJob = null
            stopKeepAlive()
            bleManager.disconnect()
            refreshNotification()
        }
    }

    // ── Connection state ──────────────────────────────────────────────────────

    private fun observeConnectionState() {
        bleManager.connectionState.onEach { state ->
            lastState = state
            updateNotification(notificationTextFor(state))

            // Keep-alive runs only while the link is up.
            if (state is BleConnectionState.Ready) {
                startKeepAlive()
                // Record the moment the link genuinely came up, for the Connection health card.
                runCatching { watchPreferences.saveLastConnectedEpoch(System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "saveLastConnectedEpoch failed: ${it.message}") }
            } else {
                stopKeepAlive()
            }

            // Always-on re-kick: BleManager gave up (Disconnected) but the user didn't stop us.
            // Schedule a delayed fresh connect; cancel any pending one whenever we leave Disconnected.
            // Suppressed while Bluetooth is off — the adapter-ON broadcast reconnects instead.
            if (state is BleConnectionState.Disconnected && !userStopped && bluetoothOn) {
                scheduleRekick()
            } else {
                rekickJob?.cancel(); rekickJob = null
            }
        }.launchIn(serviceScope)
    }

    /** Re-establish the connection a while after BleManager gave up, so the watch re-attaches on its own. */
    private fun scheduleRekick() {
        if (rekickJob?.isActive == true) return
        rekickJob = serviceScope.launch {
            Log.i(TAG, "Connection idle — re-kicking connect in ${REKICK_DELAY_MS}ms")
            delay(REKICK_DELAY_MS)
            if (userStopped) return@launch
            // Run the connect in a detached child so the observer cancelling rekickJob (when state
            // moves to Connecting) can't cancel the connect mid-flight.
            serviceScope.launch { requestConnect("re-kick") }
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
        // Persist the intent so boot / the watchdog don't resurrect what the user just stopped.
        // Uses a detached scope: serviceScope is cancelled in onDestroy moments from now.
        val prefs = watchPreferences
        CoroutineScope(SupervisorJob()).launch {
            runCatching { prefs.setAutoStartEnabled(false) }
                .onFailure { Log.w(TAG, "persisting auto-start=false failed: ${it.message}") }
        }
        ConnectionWatchdog.cancel(this)
        bleManager.disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        // Android 14 (and the connectedDevice type since Android 10) requires the foreground
        // service type to be specified at startForeground() time, backed by the manifest
        // declaration + FOREGROUND_SERVICE_CONNECTED_DEVICE permission. ServiceCompat handles the
        // per-API plumbing. Guarded so a background-start denial (Android 12+) or a missing
        // BLUETOOTH_CONNECT (the connectedDevice type requires it) can't crash us.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "startForeground failed: ${it.message}") }
    }

    /**
     * Notification text. Names the *actual* blocker where there is one — a "Watch disconnected"
     * that really means "you revoked Bluetooth permission" or "Bluetooth is off" is exactly the
     * silent failure this work exists to remove.
     */
    private fun notificationTextFor(state: BleConnectionState): String = when {
        !hasBleConnectPermission(this) -> "Bluetooth permission revoked — open NormLink"
        !bluetoothOn -> "Bluetooth is off — will reconnect when it's back on"
        state is BleConnectionState.Ready -> "Connected to ${state.deviceName}"
        state is BleConnectionState.Connecting -> "Connecting…"
        state is BleConnectionState.Discovering -> "Setting up…"
        state is BleConnectionState.Scanning -> "Scanning…"
        // After a few failed cold attempts, hint at the only reliable cure (a BT adapter reset).
        state is BleConnectionState.Error ->
            if (state.retryCount >= 3) "Trouble connecting — try toggling Bluetooth" else "Reconnecting…"
        // Idle: if the OS may be freezing us, say so — it's the likeliest cause of a long silence.
        isBatteryOptimized() -> "Watch disconnected — allow background activity in Settings"
        else -> "Watch disconnected — retrying"
    }

    private fun isBatteryOptimized(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName)?.not() ?: false

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
        // POST_NOTIFICATIONS may have been revoked; notify() then throws nothing but is dropped.
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
            .onFailure { Log.w(TAG, "notification update failed: ${it.message}") }
    }

    /** Re-render the notification for the current state (after a non-state change, e.g. adapter off). */
    private fun refreshNotification() = updateNotification(notificationTextFor(lastState))

    companion object {
        /** User-initiated clean stop (fired by the notification's Disconnect action): tears down
         *  the link and stops the service without scheduling the always-on re-kick. */
        const val ACTION_STOP = "com.norm2hacked.ble.action.STOP"

        /** Explicit user intent — the app was opened. Re-arms auto-start. */
        const val ACTION_START = "com.norm2hacked.ble.action.START"

        /** Unattended start (boot, app update, watchdog). Honours the stored auto-start flag. */
        const val ACTION_RESUME = "com.norm2hacked.ble.action.RESUME"

        /**
         * Whether the service is currently alive in this process. Read by [ConnectionWatchdog] and
         * the Connection health UI. It is process-scoped by nature: `false` after a process kill is
         * exactly the signal the watchdog needs.
         */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /** Start (or no-op if already running) the always-on connection service, on explicit user
         *  intent. Re-arms auto-start, overriding an earlier "Disconnect". */
        fun start(context: Context) = launch(context, ACTION_START)

        /** Unattended (re)start from boot, an app update, or the watchdog. Honours the stored
         *  auto-start flag, and never throws: on Android 12+ a background FGS start is rejected
         *  unless the app is exempt from battery optimisations, so the denial is logged and the
         *  next watchdog tick (or the next app launch) retries. */
        fun resume(context: Context) = launch(context, ACTION_RESUME)

        private fun launch(context: Context, action: String) {
            val intent = Intent(context, BleService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.w(TAG, "startForegroundService($action) denied: ${it.javaClass.simpleName}: " +
                            "${it.message}. Exempting the app from battery optimisations lifts this " +
                            "restriction (see the Connection health card in Settings).")
                }
        }

        /** The `connectedDevice` foreground-service type requires this permission; without it
         *  `startForeground` throws and no GATT operation can succeed. */
        fun hasBleConnectPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        fun isBluetoothOn(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.state ==
                BluetoothAdapter.STATE_ON
    }
}
