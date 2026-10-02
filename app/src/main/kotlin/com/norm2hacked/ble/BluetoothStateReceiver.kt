package com.norm2hacked.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Thin bridge for `BluetoothAdapter.ACTION_STATE_CHANGED`, mapping the adapter state onto a
 * simple on/off callback for [BleService].
 *
 * Why this matters for uptime: when the user (or the system, e.g. Airplane mode / an "optimise
 * battery" sweep) turns Bluetooth off, every GATT operation fails instantly. Without this, the
 * service burns its whole reconnect budget in a few seconds against a dead adapter, gives up, and
 * then only retries on the slow 30s re-kick — so the watch stays disconnected long after Bluetooth
 * comes back. With it we stop cleanly on OFF and reconnect the moment the adapter reports ON.
 *
 * `ACTION_STATE_CHANGED` is not an implicit-broadcast exemption, so this must be registered
 * dynamically (a manifest `<receiver>` would never fire on Android 8+).
 */
class BluetoothStateReceiver(
    private val onAdapterState: (on: Boolean) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                Log.i(TAG, "Bluetooth adapter ON")
                onAdapterState(true)
            }
            // TURNING_OFF is the earliest reliable signal — react there too so we tear down before
            // the stack starts failing GATT calls.
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                Log.i(TAG, "Bluetooth adapter OFF/TURNING_OFF (state=$state)")
                onAdapterState(false)
            }
            else -> Unit // STATE_TURNING_ON — wait for STATE_ON.
        }
    }

    private companion object {
        const val TAG = "BluetoothStateRx"
    }
}
