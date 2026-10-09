package com.normplus.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.commands.UpgradeModeCommand
import com.normplus.protocol.ota.OtaException
import com.normplus.protocol.ota.OtaTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val TAG = "BleOtaTransport"

/**
 * [OtaTransport] over [BleManager]: what `ApolloOtaSession` (in :protocol) needs from the link.
 *
 * Replies are collected from [BleManager.otaFlow] into a channel from [openDfu] until [close],
 * so none is missed between a write and the wait for it. Both DFU characteristics are written
 * without response -- the only write they have.
 */
class BleOtaTransport @Inject constructor(
    private val bleManager: BleManager,
) : OtaTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null
    private var replies = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun enterUpgradeMode() {
        val ack = bleManager.sendAndAwait(CommandCode.UPGRADE_MODE, Action.SET, UpgradeModeCommand.payload())
        // The generic acknowledgement: RESPONSE / SET_RESPONSE, payload [0x0E][status].
        val status = ack.payload.getOrNull(1)?.toInt()
        if (status != 0) throw OtaException("The watch refused UPGRADE_MODE (status $status)")
    }

    override suspend fun openDfu(): Int {
        bleManager.drainOtaWriteChannel()
        collector?.cancel()
        replies = Channel(Channel.UNLIMITED)
        val sink = replies
        collector = scope.launch { bleManager.otaFlow.collect { sink.trySend(it) } }
        val mtu = bleManager.requestMtu(MTU)
        Log.i(TAG, "DFU open, ATT MTU $mtu")
        return mtu - 3
    }

    override suspend fun writeControl(bytes: ByteArray) = write(bytes, BleConstants.CHAR_APOLLO_1531)

    override suspend fun writeData(bytes: ByteArray) = write(bytes, BleConstants.CHAR_APOLLO_1532)

    private suspend fun write(bytes: ByteArray, char: java.util.UUID) =
        bleManager.writeToCharAwait(bytes, char, mtu = bytes.size,
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    override suspend fun nextReply(timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs) { replies.receive() }

    override fun clearReplies() {
        while (replies.tryReceive().isSuccess) { /* drain */ }
    }

    override fun close() {
        collector?.cancel()
        collector = null
    }

    private companion object {
        /** What tools/watchemu's reference client asks for; 131 is the least that works. */
        const val MTU = 247
    }
}
