package com.normplus.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure call state machine and the watch→phone control decoder. No Android needed.
 */
class CallStateMachineTest {

    @Test
    fun `incoming then answered then ended`() {
        val m = CallStateMachine()
        assertEquals(listOf(CallAction.PushIncoming("123")), m.onState(CallState.RINGING, "123"))
        // answered → clear the incoming screen
        assertEquals(listOf(CallAction.PushEnded), m.onState(CallState.OFFHOOK, null))
        // call ends after being answered → single ended (no duplicate)
        assertEquals(emptyList(), m.onState(CallState.IDLE, null))
    }

    @Test
    fun `incoming then missed`() {
        val m = CallStateMachine()
        assertEquals(listOf(CallAction.PushIncoming("555")), m.onState(CallState.RINGING, "555"))
        assertEquals(
            listOf(CallAction.PushEnded, CallAction.PushMissed("555")),
            m.onState(CallState.IDLE, null),
        )
    }

    @Test
    fun `outgoing call pushes nothing`() {
        val m = CallStateMachine()
        assertEquals(emptyList(), m.onState(CallState.OFFHOOK, null)) // dialed out
        assertEquals(emptyList(), m.onState(CallState.IDLE, null))    // hung up
    }

    @Test
    fun `a late number on a second RINGING re-pushes incoming, then stops`() {
        val m = CallStateMachine()
        // First RINGING with no number (e.g. before the number is delivered)
        assertEquals(listOf(CallAction.PushIncoming("")), m.onState(CallState.RINGING, null))
        // Second RINGING carrying the number → re-push so the caller name isn't lost
        assertEquals(listOf(CallAction.PushIncoming("777")), m.onState(CallState.RINGING, "777"))
        // A third RINGING with the same number → no further push
        assertEquals(emptyList(), m.onState(CallState.RINGING, "777"))
        // …and the number is captured for a subsequent miss
        assertEquals(
            listOf(CallAction.PushEnded, CallAction.PushMissed("777")),
            m.onState(CallState.IDLE, null),
        )
    }

    @Test
    fun `machine resets between calls`() {
        val m = CallStateMachine()
        m.onState(CallState.RINGING, "111")
        m.onState(CallState.IDLE, null) // missed
        // A brand-new incoming call pushes again.
        assertEquals(listOf(CallAction.PushIncoming("222")), m.onState(CallState.RINGING, "222"))
    }

    @Test
    fun `callControlAction decodes accept reject ignore`() {
        assertEquals(CallControlAction.Accept, callControlAction(byteArrayOf(0x00)))
        assertEquals(CallControlAction.Reject, callControlAction(byteArrayOf(0x01)))
        assertEquals(CallControlAction.Reject, callControlAction(byteArrayOf(0x7F)))
        assertEquals(CallControlAction.Ignore, callControlAction(byteArrayOf()))
    }

    @Test
    fun `idle without a prior call does nothing`() {
        val m = CallStateMachine()
        assertTrue(m.onState(CallState.IDLE, null).isEmpty())
    }
}
