package com.normplus.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import com.normplus.protocol.Packet
import com.normplus.protocol.PacketDeframer
import kotlinx.coroutines.channels.SendChannel
import java.util.UUID

private const val TAG = "BleGattCallback"

class BleGattCallback(
    private val onConnectionStateChange: (gatt: BluetoothGatt, connected: Boolean) -> Unit,
    private val onServicesDiscovered: (gatt: BluetoothGatt) -> Unit,
    // Write completion is routed by characteristic UUID to avoid the shared-channel race:
    // command chars (0x8001/0x8003) → onCommandWriteComplete → BleWriteQueue
    // OTA chars (0x1531/0x1532)   → onOtaWriteComplete    → BleManager.writeToCharAwait
    private val onCommandWriteComplete: (status: Int) -> Unit,  // for 0x8001/0x8003
    private val onOtaWriteComplete: (status: Int) -> Unit,
    private val onDescriptorWriteComplete: (status: Int) -> Unit,
    private val packetChannel: SendChannel<Packet>,
    // OTA notifications (0x1531/0x1532) are sent here, NOT to packetChannel.
    private val otaNotifyChannel: SendChannel<ByteArray>,
    // The negotiated ATT MTU, for BleManager.requestMtu.
    private val onMtuChanged: (mtu: Int, status: Int) -> Unit = { _, _ -> },
) : BluetoothGattCallback() {

    private val deframer = PacketDeframer()

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        val connected = newState == BluetoothProfile.STATE_CONNECTED
        if (connected) {
            Log.i(TAG, "Connected — status=$status device=${gatt.device.address}")
        } else {
            Log.i(TAG, "Disconnected — status=$status newState=$newState device=${gatt.device.address}")
        }
        onConnectionStateChange(gatt, connected)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val summary = gatt.services.joinToString("; ") { svc ->
                "${svc.uuid.shortId()} [${svc.characteristics.joinToString { it.uuid.shortId() }}]"
            }
            Log.i(TAG, "Services discovered: $summary")
            onServicesDiscovered(gatt)
        } else {
            Log.e(TAG, "Service discovery FAILED status=$status")
        }
    }

    // ── Android 13+ (API 33): value passed directly — no stale-read risk ─────────

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        handleCharacteristicChange(characteristic.uuid, value)
    }

    // ── API 30–32: value must be read from the characteristic object ───────────

    @Deprecated("Deprecated in API 33", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        handleCharacteristicChange(characteristic.uuid, characteristic.value ?: return)
    }

    private fun handleCharacteristicChange(uuid: UUID, value: ByteArray) {
        Log.d(TAG, "← notify char=${uuid.shortId()} len=${value.size} hex=${value.toHex()}")

        if (value.contentEquals(BleConstants.COMMAND_REPEAT_ECHO)) {
            Log.d(TAG, "  [init echo filtered]")
            return
        }

        if (uuid == BleConstants.CHAR_APOLLO_1531 || uuid == BleConstants.CHAR_APOLLO_1532) {
            val stepByte = value.getOrNull(1)?.let { "0x%02X".format(it) } ?: "?"
            Log.d(TAG, "  → OTA channel step=$stepByte")
            otaNotifyChannel.trySend(value)
        } else {
            // Handle main response channels: 8002, 8004, 8005
            // All use the same deframing logic (source: BluetoothParse.smali)
            for (packet in deframer.feed(value)) {
                Log.d(TAG, "  → cmd=${packet.cmdCode} action=${packet.action} payload[${packet.payload.size}]=${packet.payload.toHex()}")
                packetChannel.trySend(packet)
            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        val uuid = characteristic.uuid
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d(TAG, "→ write OK char=${uuid.shortId()}")
        } else {
            Log.e(TAG, "→ write FAILED char=${uuid.shortId()} status=0x${"%02X".format(status)}")
        }
        when {
            uuid == BleConstants.CHAR_APOLLO_1531 || uuid == BleConstants.CHAR_APOLLO_1532 ->
                onOtaWriteComplete(status)
            // Notify chars (8002, 8004) are written to as fire-and-forget triggers.
            // Do not forward their write callbacks to commandWriteCompleteChannel —
            // that would desync the queue's write-complete handshake.
            uuid == BleConstants.CHAR_NOTIFY_8002 || uuid == BleConstants.CHAR_NOTIFY_8004 ->
                Log.d(TAG, "→ trigger write complete (ignored) char=${uuid.shortId()}")
            else ->
                onCommandWriteComplete(status)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        Log.d(TAG, "Descriptor write ${descriptor.uuid.shortId()} status=$status")
        onDescriptorWriteComplete(status)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.i(TAG, "MTU changed to $mtu status=$status")
        onMtuChanged(mtu, status)
    }

    // Result of BleManager.readRemoteRssi() (the BleService keep-alive). Logged only — the value
    // isn't used; the read itself is the keep-alive, exercising the link to keep it warm.
    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        Log.d(TAG, "keep-alive RSSI=$rssi dBm status=$status")
    }

    private fun UUID.shortId() = toString().substring(4, 8).uppercase()
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
