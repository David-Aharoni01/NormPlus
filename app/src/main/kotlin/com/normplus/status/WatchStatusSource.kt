package com.normplus.status

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.normplus.ble.BleConnectionState
import com.normplus.ble.BleManager
import com.normplus.ble.BleService
import com.normplus.data.preferences.WatchPreferences
import com.normplus.domain.usecase.SyncHealthDataUseCase
import com.normplus.domain.usecase.SyncProgress
import com.normplus.domain.usecase.SyncStage
import com.normplus.notification.NotificationListenerHealth
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.commands.BatteryCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WatchStatus"

/** A battery reading older than this is read again when the app comes to the front. */
private const val BATTERY_STALE_MS = 15 * 60_000L

/**
 * The one place the app's idea of the watch is kept (#97): the connection, the battery, the
 * last sync and the sync running now, and every blocker with its fix. The shell's status line
 * and banner, the service notification and any screen read [status]; nobody else samples the
 * system or keeps a second copy.
 *
 * Built from what was already there: the connection is [BleManager.connectionState], with the
 * attempt count kept across a reconnect's own Connecting and Setting up; the blockers are the
 * checks the Connection health card made (and the service's notification), sampled by
 * [refresh]; the sync is [SyncHealthDataUseCase], run here so it outlives the screen that
 * started it and every screen sees its progress.
 *
 * Never throws and never lets a protocol surprise out: a battery reading that fails or makes no
 * sense is logged with its bytes and the last good one is kept; a sync that fails ends as
 * [SyncState.Finished] with its problems.
 */
@Singleton
class WatchStatusSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val prefs: WatchPreferences,
    private val syncUseCase: SyncHealthDataUseCase,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "status: ${e.javaClass.simpleName}: ${e.message}", e) },
    )

    private val facts = MutableStateFlow(sample())
    private val battery = MutableStateFlow<WatchBattery?>(null)
    private val sync = MutableStateFlow<SyncState>(SyncState.Idle)
    private val syncRunning = AtomicBoolean(false)
    private val batteryMutex = Mutex()

    /**
     * The connection in the six states people see. A reconnect goes through BleManager's
     * Connecting and Discovering again; it stays "Reconnecting (attempt N)" through them, so the
     * banner does not flicker between three messages on every try.
     */
    private val link: Flow<Link> = bleManager.connectionState.scan<BleConnectionState, Link>(Link.Disconnected) { previous, state ->
        when (state) {
            is BleConnectionState.Disconnected -> Link.Disconnected
            is BleConnectionState.Scanning -> Link.Scanning
            is BleConnectionState.Connecting -> previous as? Link.Reconnecting ?: Link.Connecting
            is BleConnectionState.Discovering -> previous as? Link.Reconnecting ?: Link.SettingUp
            is BleConnectionState.Ready -> Link.Ready
            // BleManager's retry counter: 0 for a dropped link it picks up at once, then 1, 2, ...
            is BleConnectionState.Error -> Link.Reconnecting(state.retryCount)
        }
    }

    private val savedWatch: Flow<Boolean> = prefs.deviceMac.map { it != null }.orDefault(true)

    private val blockers: Flow<List<Blocker>> = combine(
        facts,
        prefs.autoStartEnabled.orDefault(true),
        BleService.isRunning,
        savedWatch,
    ) { f, autoStart, running, hasWatch ->
        buildList {
            if (!f.blePermission) add(Blocker.BluetoothPermissionMissing)
            // Stopped from its notification: not running, and told not to start on its own.
            if (hasWatch && !running && !autoStart) add(Blocker.ServiceStopped)
            if (!f.bluetoothOn) add(Blocker.BluetoothOff)
            if (f.backgroundRestricted) add(Blocker.BackgroundRestricted)
            if (!f.ignoringBatteryOptimisations) add(Blocker.BatteryOptimisationOn)
            if (!f.notificationsEnabled) add(Blocker.NotificationsBlocked)
            if (!f.notificationAccess) add(Blocker.NotificationAccessOff)
            if (!f.callsAllowed) add(Blocker.CallsNotAllowed)
        }.sortedBy { it.ordinal }
    }

    private val dismissed: Flow<Set<Blocker>> = prefs.batteryPromptDismissed.orDefault(false)
        .map { if (it) setOf(Blocker.BatteryOptimisationOn) else emptySet() }

    val status: StateFlow<WatchStatus> = combine(
        combine(savedWatch, link) { hasWatch, l -> if (hasWatch) l else Link.NoWatch },
        battery,
        prefs.lastSyncEpoch.orDefault(0L),
        sync,
        combine(blockers, dismissed) { b, d -> b to d },
    ) { l, batt, lastSync, s, (b, d) ->
        WatchStatus(
            link = l,
            battery = batt,
            lastSyncEpochMs = lastSync.takeIf { it > 0L },
            sync = s,
            blockers = b,
            dismissed = d,
        )
    }.stateIn(scope, SharingStarted.Eagerly, WatchStatus())

    init {
        registerBluetoothReceiver()
        // The battery is read on every connection, as the official app reads it with every sync
        // (SyncBluetoothDataNew$1 → PBluetooth.getBatteryPower), and again after each sync here.
        scope.launch {
            bleManager.connectionState.collect { if (it is BleConnectionState.Ready) readBattery() }
        }
    }

    // ── What the shell and the screens call ──────────────────────────────────────

    /**
     * Samples the system facts again: permissions, Bluetooth, the battery and background
     * settings, notifications. They cannot be observed, so the shell calls this whenever the
     * app comes to the front and whenever a fix returns from Android's settings.
     */
    fun refresh() {
        facts.value = sample()
    }

    /** The app came to the front: [refresh], and read the battery if the reading is old. */
    fun onAppVisible() {
        refresh()
        val reading = battery.value
        if (bleManager.isConnected && (reading == null || System.currentTimeMillis() - reading.readAtEpochMs > BATTERY_STALE_MS)) {
            scope.launch { readBattery() }
        }
    }

    /**
     * Starts a sync, unless one is running or the watch is not connected (a sync then would
     * only fail). Returns whether it started. Progress and the outcome are in [status].
     */
    fun sync(): Boolean {
        if (!bleManager.isConnected) {
            Log.i(TAG, "sync: not connected — not started")
            return false
        }
        if (!syncRunning.compareAndSet(false, true)) return false
        scope.launch {
            val problems = mutableListOf<SyncProblem>()
            try {
                sync.value = SyncState.Running(SyncStage.Counting, 0, 0)
                syncUseCase.syncAll().collect { p ->
                    when (p) {
                        is SyncProgress.Running -> sync.value = SyncState.Running(p.stage, p.current, p.total)
                        is SyncProgress.Error -> problems += SyncProblem(p.stage, p.received, p.expected, p.message)
                        SyncProgress.Done, SyncProgress.Idle -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "sync failed: ${e.javaClass.simpleName}: ${e.message}", e)
                problems += SyncProblem(null, null, null, e.message ?: e.javaClass.simpleName)
            } finally {
                sync.value = SyncState.Finished(System.currentTimeMillis(), problems.toList())
                syncRunning.set(false)
            }
            readBattery()
        }
        return true
    }

    /** Reads the battery again now (no-op while not connected). */
    fun refreshBattery() {
        scope.launch { readBattery() }
    }

    /**
     * "Not now" to battery optimisation: it stays a fix-it on Watch, but leaves the banner.
     * Remembered (WatchPreferences `battery_opt_prompt_dismissed`), as the old prompt's was.
     */
    fun dismissBatteryOptimisation() {
        scope.launch { prefs.setBatteryPromptDismissed(true) }
    }

    // ── Inside ───────────────────────────────────────────────────────────────────

    private suspend fun readBattery() = batteryMutex.withLock {
        if (!bleManager.isConnected) return@withLock
        runCatching {
            // Source: BluetoothCommandManager.smali getBatteryPower → BatteryPower(callback, 1, 0):
            // content [00]. Frame 6F 08 70 01 00 00 8F; the reply's first byte is the level,
            // its top bit set while charging (BatteryCommand.parse).
            bleManager.sendAndAwait(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
        }.onSuccess { pkt ->
            val raw = pkt.payload.firstOrNull()?.toInt()?.and(0xFF)
            val reading = BatteryCommand.parse(pkt)
            if (pkt.action != Action.CHECK_RESPONSE || raw == null || reading.percent > 100) {
                Log.w(TAG, "battery: unexpected reply action=${pkt.action} payload=${pkt.payload.toHex()} — keeping the last reading")
            } else {
                battery.value = WatchBattery(reading.percent, reading.charging, System.currentTimeMillis())
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "battery read failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The system facts behind the blockers. Each check is on its own, so one failing cannot hide the rest. */
    private data class SystemFacts(
        val blePermission: Boolean = true,
        val bluetoothOn: Boolean = true,
        val backgroundRestricted: Boolean = false,
        val ignoringBatteryOptimisations: Boolean = true,
        val notificationsEnabled: Boolean = true,
        val notificationAccess: Boolean = true,
        val callsAllowed: Boolean = true,
    )

    private fun sample(): SystemFacts {
        fun check(name: String, default: Boolean, block: () -> Boolean): Boolean =
            runCatching(block).getOrElse { Log.w(TAG, "$name: ${it.message}"); default }
        return SystemFacts(
            blePermission = check("ble permission", true) { BleService.hasBleConnectPermission(context) },
            bluetoothOn = check("bluetooth", true) { BleService.isBluetoothOn(context) },
            backgroundRestricted = check("background", false) {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false
            },
            ignoringBatteryOptimisations = check("battery optimisation", true) {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            },
            notificationsEnabled = check("notifications", true) {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            },
            notificationAccess = check("notification access", true) { NotificationListenerHealth.isPermissionGranted(context) },
            callsAllowed = check("calls", true) {
                CALL_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            },
        )
    }

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) refresh()
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED, // a protected system broadcast
            )
        }.onFailure { Log.w(TAG, "Bluetooth state receiver not registered: ${it.message}") }
    }

    /** A preference flow that cannot end the status: a read error is logged and [default] stands in. */
    private fun <T> Flow<T>.orDefault(default: T): Flow<T> = catch { e ->
        Log.w(TAG, "preferences: ${e.javaClass.simpleName}: ${e.message}")
        emit(default)
    }

    companion object {
        /**
         * The permissions phone calls need (CallManager): the call's state and number, the
         * caller's name, and answering or declining from the watch. All optional.
         */
        val CALL_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        /** Nearby devices: what the connection needs (BLUETOOTH_CONNECT) and the first run's scan. */
        val BLUETOOTH_PERMISSIONS: List<String> = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )

        /** Posting notifications (the service's own); a runtime permission from Android 13. */
        val NOTIFICATION_PERMISSIONS: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    }
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
