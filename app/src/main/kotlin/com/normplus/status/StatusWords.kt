package com.normplus.status

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.normplus.R

/** A string resource and its arguments, resolved where it is shown. */
@Immutable
data class Words(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    fun resolve(resources: Resources): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.toTypedArray())
}

/** How a status message reads: the banner's tone, without the UI's types. */
enum class Severity {
    /** Something is under way (scanning, connecting, setting up, reconnecting). */
    Working,
    /** A plain fact with a way on ("Your watch is not connected"). */
    Notice,
    /** A blocker with a one-tap fix (amber). */
    NeedsFixing,
}

/** One status message: its words, how it reads, a second line, and its one fix. */
@Immutable
data class StatusMessage(
    val text: Words,
    val severity: Severity,
    val hint: Words? = null,
    val action: Words? = null,
    val fix: Fix? = null,
)

/**
 * What the banner and the service notification say (#97). They say the same thing in the same
 * words: both are chosen here, from one [WatchStatus], and printed from `strings_shell.xml`.
 *
 * The banner names one thing at a time, in this order: a blocker that stops the link
 * ([Blocker.stopsTheLink]: the Bluetooth permission, the service stopped, Bluetooth off,
 * background restricted); then the connection in any state but Ready; then the other blockers
 * (battery optimisation, notifications blocked), unless the person said "Not now" to them.
 * Nothing at all before a watch is saved: that is the first run's business.
 */
object StatusWords {

    /** The banner under every title, or null when there is nothing to say. */
    fun banner(status: WatchStatus): StatusMessage? {
        if (status.link == Link.NoWatch) return null
        val shown = status.blockers.filter { it.inBanner && it !in status.dismissed }
        shown.firstOrNull { it.stopsTheLink }?.let { return blocker(it) }
        link(status.link)?.let { return it }
        return shown.firstOrNull()?.let(::blocker)
    }

    /**
     * The service notification's text: the banner's words for the link and for whatever stops
     * it, "Connected" once it is up. The milder blockers stay in the app.
     */
    fun notification(status: WatchStatus): StatusMessage {
        if (status.link == Link.NoWatch) return StatusMessage(Words(R.string.shell_notification_no_watch), Severity.Notice)
        status.blockers.firstOrNull { it.inBanner && it.stopsTheLink }?.let { return blocker(it) }
        return link(status.link) ?: StatusMessage(Words(R.string.shell_notification_connected), Severity.Notice)
    }

    /** The connection's state in words, or null when it is Ready (or there is no watch). */
    fun link(link: Link): StatusMessage? = when (link) {
        Link.NoWatch, Link.Ready -> null
        Link.Scanning -> StatusMessage(Words(R.string.shell_link_scanning), Severity.Working)
        Link.Connecting -> StatusMessage(Words(R.string.shell_link_connecting), Severity.Working)
        Link.SettingUp -> StatusMessage(Words(R.string.shell_link_setting_up), Severity.Working)
        Link.Disconnected -> StatusMessage(
            Words(R.string.shell_link_disconnected),
            Severity.Notice,
            action = Words(R.string.shell_fix_connect),
            fix = Fix.Connect,
        )
        is Link.Reconnecting -> when {
            link.attempt >= BLUETOOTH_HINT_AFTER_ATTEMPTS -> StatusMessage(
                Words(R.string.shell_link_reconnecting_attempt, listOf(link.attempt)),
                Severity.Working,
                hint = Words(R.string.shell_link_bluetooth_hint),
                action = Words(R.string.shell_fix_bluetooth_settings),
                fix = Fix.OpenBluetoothSettings,
            )
            link.attempt > 0 -> StatusMessage(Words(R.string.shell_link_reconnecting_attempt, listOf(link.attempt)), Severity.Working)
            else -> StatusMessage(Words(R.string.shell_link_reconnecting), Severity.Working)
        }
    }

    /** A blocker as the banner names it, with its one fix. */
    fun blocker(blocker: Blocker): StatusMessage = StatusMessage(
        text = Words(title(blocker)),
        severity = Severity.NeedsFixing,
        action = Words(fixLabel(blocker)),
        fix = blocker.fix,
    )

    /** What a blocker is called: the banner's words and a fix-it card's title. */
    @StringRes
    fun title(blocker: Blocker): Int = when (blocker) {
        Blocker.BluetoothPermissionMissing -> R.string.shell_blocker_bluetooth_permission
        Blocker.ServiceStopped -> R.string.shell_blocker_service_stopped
        Blocker.BluetoothOff -> R.string.shell_blocker_bluetooth_off
        Blocker.BackgroundRestricted -> R.string.shell_blocker_background_restricted
        Blocker.BatteryOptimisationOn -> R.string.shell_blocker_battery_optimisation
        Blocker.NotificationsBlocked -> R.string.shell_blocker_notifications_blocked
        Blocker.NotificationAccessOff -> R.string.shell_blocker_notification_access
        Blocker.CallsNotAllowed -> R.string.shell_blocker_calls
    }

    /** Why a blocker matters, for a fix-it card (the banner has no room for it). */
    @StringRes
    fun reason(blocker: Blocker): Int = when (blocker) {
        Blocker.BluetoothPermissionMissing -> R.string.shell_blocker_bluetooth_permission_reason
        Blocker.ServiceStopped -> R.string.shell_blocker_service_stopped_reason
        Blocker.BluetoothOff -> R.string.shell_blocker_bluetooth_off_reason
        Blocker.BackgroundRestricted -> R.string.shell_blocker_background_restricted_reason
        Blocker.BatteryOptimisationOn -> R.string.shell_blocker_battery_optimisation_reason
        Blocker.NotificationsBlocked -> R.string.shell_blocker_notifications_blocked_reason
        Blocker.NotificationAccessOff -> R.string.shell_blocker_notification_access_reason
        Blocker.CallsNotAllowed -> R.string.shell_blocker_calls_reason
    }

    /** The fix's label: "Turn on", "Allow", "Start". */
    @StringRes
    fun fixLabel(blocker: Blocker): Int = when (blocker) {
        Blocker.BluetoothPermissionMissing -> R.string.shell_blocker_bluetooth_permission_fix
        Blocker.ServiceStopped -> R.string.shell_blocker_service_stopped_fix
        Blocker.BluetoothOff -> R.string.shell_blocker_bluetooth_off_fix
        Blocker.BackgroundRestricted -> R.string.shell_blocker_background_restricted_fix
        Blocker.BatteryOptimisationOn -> R.string.shell_blocker_battery_optimisation_fix
        Blocker.NotificationsBlocked -> R.string.shell_blocker_notifications_blocked_fix
        Blocker.NotificationAccessOff -> R.string.shell_blocker_notification_access_fix
        Blocker.CallsNotAllowed -> R.string.shell_blocker_calls_fix
    }
}
