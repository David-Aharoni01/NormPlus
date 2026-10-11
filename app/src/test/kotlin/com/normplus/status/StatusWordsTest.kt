package com.normplus.status

import com.normplus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the banner and the notification say, and in which order (#97). */
class StatusWordsTest {

    private fun status(link: Link, vararg blockers: Blocker, dismissed: Set<Blocker> = emptySet()) =
        WatchStatus(link = link, blockers = blockers.sortedBy { it.ordinal }, dismissed = dismissed)

    @Test
    fun noBannerBeforeAWatchIsSaved() {
        assertNull(StatusWords.banner(status(Link.NoWatch, Blocker.BluetoothOff)))
    }

    @Test
    fun noBannerWhenReadyAndNothingIsInTheWay() {
        assertNull(StatusWords.banner(status(Link.Ready)))
    }

    @Test
    fun eachStateHasItsOwnWords() {
        assertEquals(R.string.shell_link_scanning, StatusWords.banner(status(Link.Scanning))!!.text.id)
        assertEquals(R.string.shell_link_connecting, StatusWords.banner(status(Link.Connecting))!!.text.id)
        assertEquals(R.string.shell_link_setting_up, StatusWords.banner(status(Link.SettingUp))!!.text.id)
        assertEquals(R.string.shell_link_reconnecting, StatusWords.banner(status(Link.Reconnecting(0)))!!.text.id)
        val disconnected = StatusWords.banner(status(Link.Disconnected))!!
        assertEquals(R.string.shell_link_disconnected, disconnected.text.id)
        assertEquals(Fix.Connect, disconnected.fix)
    }

    @Test
    fun theBluetoothHintComesAfterThreeFailedAttempts() {
        val two = StatusWords.banner(status(Link.Reconnecting(2)))!!
        assertEquals(Words(R.string.shell_link_reconnecting_attempt, listOf(2)), two.text)
        assertNull(two.hint)
        assertNull(two.fix)

        val three = StatusWords.banner(status(Link.Reconnecting(3)))!!
        assertEquals(Words(R.string.shell_link_reconnecting_attempt, listOf(3)), three.text)
        assertEquals(R.string.shell_link_bluetooth_hint, three.hint!!.id)
        assertEquals(Fix.OpenBluetoothSettings, three.fix)
    }

    @Test
    fun aBlockerThatStopsTheLinkComesBeforeTheState() {
        val banner = StatusWords.banner(status(Link.Disconnected, Blocker.BluetoothOff))!!
        assertEquals(R.string.shell_blocker_bluetooth_off, banner.text.id)
        assertEquals(Severity.NeedsFixing, banner.severity)
        assertEquals(Fix.TurnOnBluetooth, banner.fix)
    }

    @Test
    fun theMostSevereBlockerIsNamed() {
        val banner = StatusWords.banner(status(Link.Disconnected, Blocker.BluetoothPermissionMissing, Blocker.BluetoothOff))!!
        assertEquals(R.string.shell_blocker_bluetooth_permission, banner.text.id)
        assertEquals(Fix.AllowBluetooth, banner.fix)
    }

    @Test
    fun theStateComesBeforeTheMilderBlockers() {
        val reconnecting = StatusWords.banner(status(Link.Reconnecting(1), Blocker.BatteryOptimisationOn))!!
        assertEquals(R.string.shell_link_reconnecting_attempt, reconnecting.text.id)
        val ready = StatusWords.banner(status(Link.Ready, Blocker.BatteryOptimisationOn, Blocker.NotificationsBlocked))!!
        assertEquals(R.string.shell_blocker_battery_optimisation, ready.text.id)
        assertEquals(Fix.IgnoreBatteryOptimisation, ready.fix)
    }

    @Test
    fun aDismissedBlockerLeavesTheBanner() {
        val dismissed = setOf(Blocker.BatteryOptimisationOn)
        assertNull(StatusWords.banner(status(Link.Ready, Blocker.BatteryOptimisationOn, dismissed = dismissed)))
    }

    @Test
    fun fixItsThatAreNotForTheBannerStayOutOfIt() {
        assertNull(StatusWords.banner(status(Link.Ready, Blocker.NotificationAccessOff, Blocker.CallsNotAllowed)))
    }

    @Test
    fun theNotificationSaysWhatTheBannerSays() {
        for (link in listOf(Link.Scanning, Link.Connecting, Link.SettingUp, Link.Reconnecting(4), Link.Disconnected)) {
            assertEquals(StatusWords.banner(status(link)), StatusWords.notification(status(link)))
        }
        val off = status(Link.Disconnected, Blocker.BluetoothOff)
        assertEquals(StatusWords.banner(off), StatusWords.notification(off))
    }

    @Test
    fun theNotificationSaysConnectedOnceReady() {
        val ready = status(Link.Ready, Blocker.BatteryOptimisationOn)
        assertEquals(R.string.shell_notification_connected, StatusWords.notification(ready).text.id)
        assertEquals(R.string.shell_notification_no_watch, StatusWords.notification(status(Link.NoWatch)).text.id)
    }

    @Test
    fun everyBlockerHasItsOwnWords() {
        val titles = Blocker.entries.map(StatusWords::title)
        val reasons = Blocker.entries.map(StatusWords::reason)
        assertEquals(titles.size, titles.toSet().size)
        assertEquals(reasons.size, reasons.toSet().size)
        assertEquals(Blocker.entries.size, Blocker.entries.map { it.fix }.toSet().size)
    }
}
