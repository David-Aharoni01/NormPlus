package com.normplus.ui.screens.firstrun

import com.normplus.ble.BondState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirstRunModelTest {

    private val none = Grants()
    private val all = Grants(bluetooth = true, notifications = true, notificationAccess = true, calls = true, battery = true)

    // ── parseWatchAddress ──

    @Test fun colonAddress() = assertEquals("4C:59:80:12:44:F1", parseWatchAddress(" 4c:59:80:12:44:f1 "))

    @Test fun bareHex() = assertEquals("4C:59:80:12:44:F1", parseWatchAddress("4c59801244f1"))

    @Test fun qrText() = assertEquals("4C:59:80:12:44:F1", parseWatchAddress("Info=Norm2#00000|4C59801244F1|123|F0.2B01|P03B"))

    @Test fun qrTextWithoutHexMac() = assertNull(parseWatchAddress("Info=Norm2|nothex|1"))

    @Test fun nonsense() {
        assertNull(parseWatchAddress("Norm 2"))
        assertNull(parseWatchAddress("4C:59:80:12:44"))
        assertNull(parseWatchAddress(""))
    }

    // ── the steps ──

    @Test fun freshRunVisitsEveryStep() {
        val seen = generateSequence(Place(FirstRunStep.Welcome)) { nextPlace(it, none) }.toList()
        assertEquals(
            listOf(
                Place(FirstRunStep.Welcome), Place(FirstRunStep.Bluetooth), Place(FirstRunStep.Find),
                Place(FirstRunStep.Connect), Place(FirstRunStep.Bind),
                Place(FirstRunStep.Notifications, NotificationPart.Post),
                Place(FirstRunStep.Notifications, NotificationPart.Access),
                Place(FirstRunStep.Notifications, NotificationPart.Apps),
                Place(FirstRunStep.Calls), Place(FirstRunStep.Battery),
            ),
            seen,
        )
    }

    @Test fun grantedStepsAreSkipped() {
        val seen = generateSequence(Place(FirstRunStep.Welcome)) { nextPlace(it, all) }.map { it.step }.toList()
        // Choosing apps is not a permission: it is always offered.
        assertEquals(
            listOf(FirstRunStep.Welcome, FirstRunStep.Find, FirstRunStep.Connect, FirstRunStep.Bind, FirstRunStep.Notifications),
            seen,
        )
        assertNull(nextPlace(Place(FirstRunStep.Notifications, NotificationPart.Apps), all))
    }

    @Test fun aLinkAlreadyUpGoesStraightToTheBind() =
        assertEquals(Place(FirstRunStep.Bind), nextPlace(Place(FirstRunStep.Welcome), all, linked = true))

    @Test fun backWalksThePermissionsButNeverIntoTheConnection() {
        assertEquals(Place(FirstRunStep.Welcome), previousPlace(Place(FirstRunStep.Find), all))
        assertEquals(Place(FirstRunStep.Bluetooth), previousPlace(Place(FirstRunStep.Find), none))
        assertNull(previousPlace(Place(FirstRunStep.Connect), none))
        assertNull(previousPlace(Place(FirstRunStep.Bind), none))
        assertNull(previousPlace(Place(FirstRunStep.Notifications, NotificationPart.Post), none))
        assertEquals(
            Place(FirstRunStep.Notifications, NotificationPart.Apps),
            previousPlace(Place(FirstRunStep.Calls), none),
        )
        assertNull(previousPlace(Place(FirstRunStep.Welcome), none))
    }

    // ── connectPhase ──

    private fun phase(
        link: LinkFact = LinkFact.Connecting,
        attempt: Int = 0,
        bond: BondState = BondState.None,
        seconds: Int = 0,
        closed: Boolean = false,
        started: Boolean = true,
    ) = connectPhase(link, attempt, bond, seconds, closed, started)

    @Test fun connectingFirstTry() = assertEquals(ConnectPhase.Connecting(0), phase())

    @Test fun notYetStartedIsNotGivingUp() = assertEquals(ConnectPhase.Connecting(0), phase(link = LinkFact.Idle, started = false))

    @Test fun retryCountIsShown() = assertEquals(ConnectPhase.Connecting(3), phase(link = LinkFact.Retrying, attempt = 3))

    @Test fun pairingCountsDown() = assertEquals(ConnectPhase.Pairing(24), phase(bond = BondState.Bonding, seconds = 6))

    @Test fun pairingWindowRunsOut() = assertEquals(ConnectPhase.PairingClosed, phase(bond = BondState.Bonding, seconds = 30))

    @Test fun pairingDeclined() = assertEquals(ConnectPhase.PairingClosed, phase(link = LinkFact.Retrying, attempt = 1, closed = true))

    @Test fun aLinkThatComesUpAnywayIsShownHonestly() =
        assertEquals(ConnectPhase.SettingUp(0), phase(link = LinkFact.SettingUp, closed = true))

    @Test fun gaveUp() = assertEquals(ConnectPhase.GaveUp, phase(link = LinkFact.Idle))
}
