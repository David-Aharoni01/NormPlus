package com.norm2hacked.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Receives `android.intent.action.PHONE_STATE` and maps the platform call state + incoming number
 * onto a [CallState], handing it to [onState]. The state-machine logic lives in [CallStateMachine];
 * this is just the thin Android bridge (mirrors the original `PhoneCallReceiver`).
 *
 * The `incoming_number` extra is only populated when READ_CALL_LOG is held (modern Android);
 * without it [number] is null and the watch shows the call with no caller info.
 */
class PhoneStateReceiver(
    private val onState: (state: CallState, number: String?) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallState.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> CallState.IDLE
            else -> return
        }
        // Diagnostic only — number length, not the value (PII).
        Log.d("PhoneStateReceiver", "PHONE_STATE=$stateStr numberLen=${number?.length ?: -1}")
        onState(state, number)
    }
}
