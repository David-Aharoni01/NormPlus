package com.normplus.status

import androidx.compose.runtime.Immutable
import com.normplus.domain.usecase.SyncStage

/**
 * Everything the app knows about the watch and what stands in its way, in one value (#97).
 * [WatchStatusSource] keeps it; the shell's status line and banner, the service notification
 * and any screen read the same one, so they never disagree.
 */
@Immutable
data class WatchStatus(
    val link: Link = Link.NoWatch,
    /** The last battery reading, kept after the link drops; check [WatchBattery.readAtEpochMs]. */
    val battery: WatchBattery? = null,
    /** When the last sync finished; null if it never has. */
    val lastSyncEpochMs: Long? = null,
    val sync: SyncState = SyncState.Idle,
    /** Every blocker that holds now, most severe first, banner or not. */
    val blockers: List<Blocker> = emptyList(),
    /** Blockers the person said "Not now" to: still fix-its on Watch, no longer in the banner. */
    val dismissed: Set<Blocker> = emptySet(),
) {
    val isReady: Boolean get() = link == Link.Ready
    val isSyncing: Boolean get() = sync is SyncState.Running
}

/**
 * The connection, in the six states people see (Scanning, Connecting, Setting up, Ready,
 * Reconnecting, Disconnected), plus [NoWatch] before the first run has saved one.
 */
sealed interface Link {
    /** No watch is saved: the first run. */
    data object NoWatch : Link
    /** Nothing is being tried just now; the service tries again on its own (after ~30 s). */
    data object Disconnected : Link
    data object Scanning : Link
    data object Connecting : Link
    /** Connected, finding the watch's services and turning its notifications on. */
    data object SettingUp : Link
    /**
     * A connection that failed or dropped, being tried again. [attempt] counts the failed
     * attempts so far; 0 is a link that just dropped and is picked up again at once.
     */
    data class Reconnecting(val attempt: Int) : Link
    data object Ready : Link
}

/** After this many failed attempts the banner and the notification add the Bluetooth hint. */
const val BLUETOOTH_HINT_AFTER_ATTEMPTS = 3

/** A battery reading: [percent] 0–100, as the watch reports it (BATTERY_POWER). */
@Immutable
data class WatchBattery(val percent: Int, val charging: Boolean, val readAtEpochMs: Long)

/** The sync, as the status line and Today show it. */
sealed interface SyncState {
    data object Idle : SyncState

    /** [done] of [total] records of [stage]; [total] is 0 while counting. */
    data class Running(val stage: SyncStage, val done: Int, val total: Int) : SyncState

    /** The last sync ended at [atEpochMs]; it stopped short in each of [problems]. */
    data class Finished(val atEpochMs: Long, val problems: List<SyncProblem>) : SyncState {
        val complete: Boolean get() = problems.isEmpty()
    }
}

/**
 * A part of a sync that failed or stopped short. [received] of [expected] records when a
 * stream stopped short; [detail] is the raw reason, for a Details view, never the headline.
 */
@Immutable
data class SyncProblem(val stage: SyncStage?, val received: Int?, val expected: Int?, val detail: String)

/**
 * What stands between the watch and a working Norm+, most severe first (the declaration order
 * is the order the banner picks in). Each has one [fix].
 *
 * @property inBanner whether the banner names it on every screen. The others are fix-its for
 *   the screens that need them (Watch, Notification apps), not for the banner.
 * @property stopsTheLink whether the watch cannot stay connected while it holds. These come
 *   before the connection state in the banner; the rest come after it.
 */
enum class Blocker(val inBanner: Boolean, val stopsTheLink: Boolean, val fix: Fix) {
    BluetoothPermissionMissing(inBanner = true, stopsTheLink = true, fix = Fix.AllowBluetooth),
    ServiceStopped(inBanner = true, stopsTheLink = true, fix = Fix.StartService),
    BluetoothOff(inBanner = true, stopsTheLink = true, fix = Fix.TurnOnBluetooth),
    BackgroundRestricted(inBanner = true, stopsTheLink = true, fix = Fix.AllowBackground),
    BatteryOptimisationOn(inBanner = true, stopsTheLink = false, fix = Fix.IgnoreBatteryOptimisation),
    NotificationsBlocked(inBanner = true, stopsTheLink = false, fix = Fix.AllowNotifications),
    /** Notification access is off: nothing is forwarded to the watch. */
    NotificationAccessOff(inBanner = false, stopsTheLink = false, fix = Fix.OpenNotificationAccess),
    /** A call permission is missing: calls do not reach the watch. Optional (first run, #98). */
    CallsNotAllowed(inBanner = false, stopsTheLink = false, fix = Fix.AllowCalls),
}

/** The one-tap fixes. `rememberStatusFixes()` (ui/shell) carries each one out. */
enum class Fix {
    /** Ask for Nearby devices; Android's app settings once it will not ask again. */
    AllowBluetooth,
    /** Start the connection service again (it re-arms starting on its own). */
    StartService,
    /** Android's "turn on Bluetooth" dialog. */
    TurnOnBluetooth,
    /** The app's settings, where background use is allowed. */
    AllowBackground,
    /** Android's "let the app always run in the background" dialog. */
    IgnoreBatteryOptimisation,
    /** Ask to post notifications; the app's notification settings once it will not ask again. */
    AllowNotifications,
    /** Android's notification access settings. */
    OpenNotificationAccess,
    /** Ask for the call permissions; the app's settings once it will not ask again. */
    AllowCalls,
    /** Try to connect now, instead of waiting for the service's next try. */
    Connect,
    /** Android's Bluetooth settings, to turn it off and on. */
    OpenBluetoothSettings,
}
