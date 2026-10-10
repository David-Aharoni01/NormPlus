package com.normplus.ui.screens.firstrun

import androidx.compose.runtime.Immutable
import com.normplus.ble.BondState

/*
 * The first run's state (#98), and the pure rules behind it: which step comes next, what the
 * connection looks like to a person, and how a typed address or QR text becomes a MAC. Kept
 * free of Android so the rules are unit-tested (FirstRunModelTest) and the screen is rendered
 * from a hand-made state (FirstRunSnapshotTest).
 */

/** The steps, in the order the brief (#95 §6) gives them; [number] is what the step pill says. */
enum class FirstRunStep(val number: Int) {
    Welcome(1),
    Bluetooth(2),
    Find(3),
    /** Connecting, with Android's pairing request inside it (the brief's steps 4 and 5). */
    Connect(4),
    /** The watch's own bind handshake (BindWatchUseCase). */
    Bind(5),
    Notifications(6),
    Calls(7),
    Battery(8),
    ;

    companion object {
        /** How many steps the pill counts; Today, the brief's step 10, is where the run ends. */
        val COUNT = entries.size
    }
}

/** The notification step's three parts, asked one after another. */
enum class NotificationPart { Post, Access, Apps }

/** A watch the scan found: its advertised name and its address. */
@Immutable
data class FoundWatch(val name: String?, val address: String)

/** The scan on the Find step. */
enum class ScanState { Idle, Scanning, Finished, Failed }

/** What Norm+ is allowed to do: the system facts the first run asks for, one step each. */
@Immutable
data class Grants(
    val bluetooth: Boolean = false,
    val notifications: Boolean = false,
    val notificationAccess: Boolean = false,
    val calls: Boolean = false,
    val battery: Boolean = false,
)

/** A permission the first run has asked for: asked and still not granted reads as denied. */
enum class Ask { Bluetooth, Notifications, NotificationAccess, Calls, Battery }

/**
 * The connection on the Connect step, as a person sees it. BleManager's own states underneath
 * are unchanged (Connecting, Discovering, Ready, Error with its retry count, Disconnected);
 * [connectPhase] words them.
 */
sealed interface ConnectPhase {
    /** Opening the link. [attempt] counts BleManager's failed tries so far (0: the first). */
    data class Connecting(val attempt: Int) : ConnectPhase

    /** Android's "Pair with …?" request is open; it closes [secondsLeft] from now. */
    data class Pairing(val secondsLeft: Int) : ConnectPhase

    /** The pairing request closed without a bond: declined, or nobody tapped Pair in time. */
    data object PairingClosed : ConnectPhase

    /** Connected; finding the watch's services and turning its notifications on. */
    data class SettingUp(val attempt: Int) : ConnectPhase

    /** BleManager tried every attempt it has and stopped. */
    data object GaveUp : ConnectPhase
}

/** The watch's bind handshake, once the link is up (BindWatchUseCase). */
sealed interface BindPhase {
    data object NotStarted : BindPhase
    data object Binding : BindPhase
    /** bindStart, setDateTime, bindEnd all acknowledged: the watch shows "Pairing Success". */
    data object Bound : BindPhase
    /** checkInit read 1: the watch was set up already, nothing was sent. */
    data object AlreadyBound : BindPhase
    /** A step was refused or unanswered; [step] and [detail] are for the log and Details. */
    data class Failed(val step: String, val detail: String) : BindPhase

    val done: Boolean get() = this == Bound || this == AlreadyBound
}

@Immutable
data class FirstRunUiState(
    val step: FirstRunStep = FirstRunStep.Welcome,
    val notificationPart: NotificationPart = NotificationPart.Post,
    val grants: Grants = Grants(),
    val asked: Set<Ask> = emptySet(),
    val bluetoothOn: Boolean = true,
    val scan: ScanState = ScanState.Idle,
    val found: List<FoundWatch> = emptyList(),
    val address: String = "",
    val addressInvalid: Boolean = false,
    /** The watch being connected to, once one is chosen. */
    val watch: FoundWatch? = null,
    val connect: ConnectPhase = ConnectPhase.Connecting(0),
    /** Android holds a bond with [watch]: its pairing request was accepted, now or before. */
    val paired: Boolean = false,
    val bind: BindPhase = BindPhase.NotStarted,
    /** Notification apps was opened from the Apps part. */
    val appsOpened: Boolean = false,
    /** The run is over: the shell goes to Today. */
    val finished: Boolean = false,
) {
    fun denied(ask: Ask): Boolean = ask in asked && !grants.has(ask)
}

fun Grants.has(ask: Ask): Boolean = when (ask) {
    Ask.Bluetooth -> bluetooth
    Ask.Notifications -> notifications
    Ask.NotificationAccess -> notificationAccess
    Ask.Calls -> calls
    Ask.Battery -> battery
}

/** A place in the run: a step, and for Notifications its part. */
data class Place(val step: FirstRunStep, val part: NotificationPart = NotificationPart.Post)

/** Every place in order. */
private val PLACES: List<Place> = FirstRunStep.entries.flatMap { step ->
    if (step == FirstRunStep.Notifications) NotificationPart.entries.map { Place(step, it) } else listOf(Place(step))
}

/**
 * Whether [place] is shown: a permission step only while its permission is missing (a run after
 * "Forget this watch" skips what is already allowed); the others always.
 */
fun shows(place: Place, grants: Grants): Boolean = when (place.step) {
    FirstRunStep.Bluetooth -> !grants.bluetooth
    FirstRunStep.Notifications -> when (place.part) {
        NotificationPart.Post -> !grants.notifications
        NotificationPart.Access -> !grants.notificationAccess
        NotificationPart.Apps -> true
    }
    FirstRunStep.Calls -> !grants.calls
    FirstRunStep.Battery -> !grants.battery
    else -> true
}

/**
 * The place after [from], skipping what [grants] already allow; null when the run is over.
 * With the link already up and the bind under way or done ([linked]: a link that came up
 * before Find), Find and Connect are skipped and the run goes to the bind.
 */
fun nextPlace(from: Place, grants: Grants, linked: Boolean = false): Place? =
    PLACES.drop(PLACES.indexOf(from) + 1).firstOrNull { p ->
        shows(p, grants) && !(linked && (p.step == FirstRunStep.Find || p.step == FirstRunStep.Connect))
    }

/**
 * Where system Back goes from [from]: the place before it that is shown, but never back into
 * the connection (Connect, Bind) and never out of it: from Connect, Bind and the first place
 * after Bind, Back is the system's (it leaves the app; the connection carries on).
 */
fun previousPlace(from: Place, grants: Grants): Place? {
    if (from.step == FirstRunStep.Connect || from.step == FirstRunStep.Bind) return null
    val before = PLACES.take(PLACES.indexOf(from)).lastOrNull { shows(it, grants) } ?: return null
    return before.takeUnless { it.step == FirstRunStep.Connect || it.step == FirstRunStep.Bind }
}

/** The link as the Connect step reads it from BleManager's state. */
enum class LinkFact { Idle, Scanning, Connecting, SettingUp, Ready, Retrying }

/** How long Android's pairing request stays open (AndroidBonder waits as long, 30 s). */
const val PAIRING_WINDOW_SECONDS = 30

/**
 * The Connect step's phase.
 *
 * @param link BleManager's state now; [attempt] its retry count.
 * @param bond Android's bond with the watch, and [bondingSeconds] how long it has been Bonding.
 * @param pairingClosed a request opened and closed without a bond since the last attempt began.
 * @param started BleManager has left Idle since this attempt was asked for, so an Idle now means
 *   it gave up rather than that it has not begun.
 */
fun connectPhase(
    link: LinkFact,
    attempt: Int,
    bond: BondState,
    bondingSeconds: Int,
    pairingClosed: Boolean,
    started: Boolean,
): ConnectPhase = when {
    link == LinkFact.SettingUp || link == LinkFact.Ready -> ConnectPhase.SettingUp(attempt)
    bond == BondState.Bonding && bondingSeconds < PAIRING_WINDOW_SECONDS ->
        ConnectPhase.Pairing(PAIRING_WINDOW_SECONDS - bondingSeconds)
    bond == BondState.Bonding || pairingClosed -> ConnectPhase.PairingClosed
    link == LinkFact.Idle && started -> ConnectPhase.GaveUp
    else -> ConnectPhase.Connecting(attempt)
}

/**
 * The watch's address from what a person typed or pasted: "AA:BB:CC:DD:EE:FF", the twelve hex
 * digits without colons, or the text of the watch's QR code (Settings → About), whose full form
 * is "Info=<name>|<MAC without colons>|<id>|<version>|<type>" (ConnectQRCodePairFragment.smali).
 * Null when it is none of those.
 */
fun parseWatchAddress(input: String): String? {
    val text = input.trim()
    fun colons(hex: String) = hex.uppercase().chunked(2).joinToString(":")
    val hex12 = Regex("[0-9A-Fa-f]{12}")
    return when {
        text.contains("Info=") && text.contains("|") ->
            text.split("|").getOrNull(1)?.trim()?.takeIf { hex12.matches(it) }?.let(::colons)
        hex12.matches(text) -> colons(text)
        Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(text) -> text.uppercase()
        else -> null
    }
}
