package com.norm2hacked.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.norm2hacked.ble.BleManager
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.MessageNewCommand
import com.norm2hacked.protocol.commands.NotificationPushCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CallManager"

// Android can deliver the caller number on a *second* RINGING broadcast a few ms after the first
// (observed ~3ms on Samsung). We delay the incoming push by this window and replace it on each
// update, so the watch gets a single incoming push that already carries the caller name.
private const val INCOMING_COALESCE_MS = 250L

/**
 * Bridges phone calls to the watch and the watch's answer/reject back to the phone.
 *
 * Outbound: a [PhoneStateReceiver] drives a [CallStateMachine]; the resulting [CallAction]s are
 * sent as MessageNewBT pushes (cmd 0x76) with the call type byte — incoming `0x05` (title=name),
 * missed `0x00` (title=name, content=number, dated), ended `0x06` (clears the watch screen).
 *
 * Inbound: the watch sends cmd `0xDC` (INCOME_CALL_RESPONSE) with byte[0] 0=accept / non-zero=
 * reject; we drive [TelecomManager] accordingly.
 *
 * Lifecycle is owned by [com.norm2hacked.ble.BleService] (start/stop with the connected session).
 */
@Singleton
class CallManager @Inject constructor(
    private val bleManager: BleManager,
) {
    private val machine = CallStateMachine()
    private var receiver: PhoneStateReceiver? = null
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var inboundJob: Job? = null
    private var incomingJob: Job? = null

    /** Begin listening for call state (outbound) and watch responses (inbound). Idempotent. */
    fun start(context: Context, scope: CoroutineScope) {
        if (receiver != null) return
        val app = context.applicationContext
        appContext = app
        this.scope = scope

        val r = PhoneStateReceiver { state, number -> onCallState(state, number) }
        receiver = r
        ContextCompat.registerReceiver(
            app,
            r,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // PHONE_STATE is a protected system broadcast
        )

        inboundJob = bleManager.parsedFlow
            .filter { it.cmdCode == CommandCode.INCOME_CALL_RESPONSE }
            .onEach { handleControl(it.payload) }
            .launchIn(scope)

        Log.i(TAG, "CallManager started")
    }

    fun stop() {
        receiver?.let { r -> runCatching { appContext?.unregisterReceiver(r) } }
        receiver = null
        inboundJob?.cancel(); inboundJob = null
        incomingJob?.cancel(); incomingJob = null
        Log.i(TAG, "CallManager stopped")
    }

    // Called on the main thread from PhoneStateReceiver, so the incomingJob swap is single-threaded.
    private fun onCallState(state: CallState, number: String?) {
        val actions = machine.onState(state, number)
        if (actions.isEmpty()) return
        val ctx = appContext ?: return
        val sc = scope ?: return

        // Incoming is delayed by a short window and replaced on each update so the late-arriving
        // caller number collapses into a single push that already carries the name.
        val incoming = actions.filterIsInstance<CallAction.PushIncoming>().lastOrNull()
        if (incoming != null) {
            incomingJob?.cancel()
            incomingJob = sc.launch {
                delay(INCOMING_COALESCE_MS)
                val name = ContactResolver.displayName(ctx, incoming.number)
                Log.d(TAG, "incoming: numberLen=${incoming.number.length} resolvedToContact=${name != incoming.number && name.isNotBlank()}")
                push(NotificationPushCommand.TYPE_INCOMING_CALL, name, "")
            }
            return
        }

        // Ended / missed supersede any pending incoming (e.g. a very short ring).
        incomingJob?.cancel(); incomingJob = null
        sc.launch {
            for (action in actions) when (action) {
                CallAction.PushEnded -> push(NotificationPushCommand.TYPE_CALL_ENDED, "", "")
                is CallAction.PushMissed -> {
                    val name = ContactResolver.displayName(ctx, action.number)
                    Log.d(TAG, "missed: numberLen=${action.number.length} resolvedToContact=${name != action.number && name.isNotBlank()}")
                    push(NotificationPushCommand.TYPE_MISSED_CALL, name, action.number)
                }
                is CallAction.PushIncoming -> {} // handled above (coalesced)
            }
        }
    }

    private suspend fun push(type: Byte, title: String, content: String) {
        Log.i(TAG, "call push type=0x${"%02X".format(type)} titleLen=${title.length}")
        bleManager.sendCommandNoResponse(
            CommandCode.SOCIAL_EX_PUSH, Action.SET,
            MessageNewCommand.appNotificationPayload(type, title, content),
            urgent = true,
        )
    }

    // acceptRingingCall()/endCall() are deprecated for default-dialer apps but remain the correct
    // path for a permission-holding companion app (matching the original NORM app).
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // guarded by hasAnswerPermission()
    private fun handleControl(payload: ByteArray) {
        val action = callControlAction(payload)
        if (action == CallControlAction.Ignore) return
        val ctx = appContext ?: return
        if (!hasAnswerPermission(ctx)) {
            Log.w(TAG, "watch call-control ($action) ignored — ANSWER_PHONE_CALLS not granted")
            return
        }
        val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return
        runCatching {
            when (action) {
                CallControlAction.Accept -> telecom.acceptRingingCall()
                CallControlAction.Reject -> telecom.endCall()
                CallControlAction.Ignore -> {}
            }
        }.onFailure { Log.w(TAG, "call-control $action failed: ${it.message}") }
        Log.i(TAG, "watch call-control: $action")
    }

    private fun hasAnswerPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED
}
