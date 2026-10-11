package com.normplus.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BondStates"

/** Android's bond with one device, as the first run shows it (#98). */
enum class BondState { None, Bonding, Bonded }

/**
 * Watches Android's bond with a device, read only: it never bonds or unbonds anything.
 * [AndroidBonder] does the bonding inside `BleManager.connect`, exactly as before; this only
 * lets the first run see when Android's "Pair with Norm2#…?" request is open (Bonding), was
 * accepted (Bonded) or closed without a bond (back to None: declined, or the 30 s ran out).
 */
@Singleton
@SuppressLint("MissingPermission")
class BondStates @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The bond with [mac] now, then every change, until the collector stops. Never throws. */
    fun of(mac: String): Flow<BondState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (!dev?.address.equals(mac, ignoreCase = true)) return
                trySend(bondState(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)))
            }
        }
        runCatching {
            // A protected system broadcast, as AndroidBonder receives it.
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "bond receiver not registered: ${it.message}") }
        runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.getRemoteDevice(mac)?.bondState
        }.onSuccess { if (it != null) trySend(bondState(it)) }
            .onFailure { Log.w(TAG, "bond state of $mac unreadable: ${it.message}") }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()

    private fun bondState(raw: Int) = when (raw) {
        BluetoothDevice.BOND_BONDED -> BondState.Bonded
        BluetoothDevice.BOND_BONDING -> BondState.Bonding
        else -> BondState.None
    }
}
