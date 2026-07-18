package com.norm2hacked.call

/**
 * Pure, Android-free state machine that turns a stream of telephony call states into the call
 * events we forward to the watch. Mirrors the original NORM app's `PhoneCallReceiver` logic:
 * `RINGING`→incoming, `OFFHOOK`→answered, `IDLE`→ended, where **RINGING→IDLE without OFFHOOK is a
 * missed call**. Outgoing calls (IDLE→OFFHOOK) produce nothing.
 *
 * Kept pure so it's unit-testable without Android; [PhoneStateReceiver] maps the platform
 * `TelephonyManager.EXTRA_STATE_*` values onto [CallState] and feeds them here.
 */
class CallStateMachine {

    private var last: CallState = CallState.IDLE
    private var savedNumber: String? = null
    // The number we last emitted an incoming push for; null = no incoming pushed for this call yet.
    private var pushedNumber: String? = null

    /**
     * Advance the machine with the latest [state] (and the best-known caller [number], which the
     * platform may deliver on a *later* RINGING broadcast than the first — observed on Samsung:
     * RINGING with no number, then RINGING with the number ~3 ms later). Returns the actions to
     * perform, in order. Incoming is re-emitted when the number first arrives so the caller name
     * isn't lost; [CallManager] coalesces the two into a single push.
     */
    fun onState(state: CallState, number: String?): List<CallAction> {
        if (!number.isNullOrBlank()) savedNumber = number
        val prev = last
        last = state

        return when (state) {
            CallState.RINGING -> {
                val num = savedNumber.orEmpty()
                when {
                    // First RINGING for this call → show the incoming screen (number may be blank).
                    pushedNumber == null -> { pushedNumber = num; listOf(CallAction.PushIncoming(num)) }
                    // The number arrived on a later broadcast → update the incoming push.
                    num.isNotBlank() && num != pushedNumber -> { pushedNumber = num; listOf(CallAction.PushIncoming(num)) }
                    else -> emptyList()
                }
            }

            CallState.OFFHOOK ->
                // Answered an incoming call → clear the watch's incoming screen. Outgoing
                // (IDLE→OFFHOOK) pushes nothing.
                if (prev == CallState.RINGING) { reset(); listOf(CallAction.PushEnded) } else emptyList()

            CallState.IDLE -> {
                val actions = if (prev == CallState.RINGING) {
                    // Rang then stopped without being answered → clear the screen + show missed.
                    listOf(CallAction.PushEnded, CallAction.PushMissed(savedNumber.orEmpty()))
                } else {
                    emptyList() // answered-call end (already cleared on answer) or outgoing
                }
                reset()
                actions
            }
        }
    }

    private fun reset() {
        savedNumber = null
        pushedNumber = null
    }
}

enum class CallState { IDLE, RINGING, OFFHOOK }

sealed interface CallAction {
    /** Incoming call ringing — show the caller on the watch (title=name, content empty). */
    data class PushIncoming(val number: String) : CallAction
    /** Missed call — title=name, content=number, with a timestamp. */
    data class PushMissed(val number: String) : CallAction
    /** Clear the incoming-call screen on the watch (answered, ended, or about to show missed). */
    data object PushEnded : CallAction
}

/** What the watch's incoming-call response (cmd 0xDC) asks us to do. */
enum class CallControlAction { Accept, Reject, Ignore }

/**
 * Decode the watch→phone incoming-call response payload: byte[0] `0x00` = accept, present and
 * non-zero = reject, empty = ignore. Source: RemoteControlManager$1.setIncomeCallResponse.
 */
fun callControlAction(payload: ByteArray): CallControlAction = when {
    payload.isEmpty() -> CallControlAction.Ignore
    payload[0].toInt() == 0 -> CallControlAction.Accept
    else -> CallControlAction.Reject
}
