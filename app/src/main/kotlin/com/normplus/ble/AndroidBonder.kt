package com.normplus.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidBonder"

/**
 * Android implementation of [WatchBonder] — mirrors the NORM companion app's
 * `bluetooth_bond/BluetoothUtils.smali`: bond via [BluetoothDevice.createBond]
 * when the device isn't already bonded, honoring [BondingPolicy].
 */
@Singleton
@SuppressLint("MissingPermission")
class AndroidBonder @Inject constructor(
    @ApplicationContext private val context: Context,
) : WatchBonder {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override suspend fun ensureBonded(mac: String): BondResult {
        val device = runCatching { adapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return BondResult.Failed("device $mac not resolvable")

        if (device.bondState == BluetoothDevice.BOND_BONDED) return BondResult.AlreadyBonded

        val result = CompletableDeferred<BondResult>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> if (!result.isCompleted) result.complete(BondResult.Bonded)
                    BluetoothDevice.BOND_NONE -> if (!result.isCompleted) result.complete(BondResult.Failed("bond failed/removed"))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        return try {
            Log.i(TAG, "createBond($mac)")
            if (!device.createBond()) return BondResult.Failed("createBond() returned false")
            withTimeoutOrNull(30_000) { result.await() } ?: BondResult.Failed("bonding timed out")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
