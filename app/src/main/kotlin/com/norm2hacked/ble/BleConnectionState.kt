package com.norm2hacked.ble

import android.bluetooth.BluetoothDevice

sealed class BleConnectionState {
    data object Disconnected : BleConnectionState()
    data object Scanning : BleConnectionState()
    data class Connecting(val device: BluetoothDevice) : BleConnectionState()
    data class Discovering(val device: BluetoothDevice) : BleConnectionState()
    data class Ready(val device: BluetoothDevice, val deviceName: String) : BleConnectionState()
    data class Error(val message: String, val retryCount: Int = 0) : BleConnectionState()

    val isConnected: Boolean get() = this is Ready
    val deviceOrNull: BluetoothDevice? get() = when (this) {
        is Connecting -> device
        is Discovering -> device
        is Ready -> device
        else -> null
    }
}
