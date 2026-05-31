package com.norm2hacked.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "BleWriteQueue"

class BleTimeoutException(cmd: CommandCode) : Exception("BLE timeout waiting for response to $cmd")
class GattWriteException(message: String) : Exception(message)

data class BleRequest(
    val bytes: ByteArray,
    val charUuid: java.util.UUID,
    val expectedCmd: CommandCode,
    val timeoutMs: Long,
    val urgent: Boolean = false,
    val deferred: CompletableDeferred<Packet> = CompletableDeferred(),
) {
    override fun equals(other: Any?) = other is BleRequest && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
}

/**
 * Coroutine actor that serialises ALL BLE command writes.
 *
 * This is the single write path for the main channel (0x8001/0x8003).
 * OTA writes bypass this queue and go directly through BleManager.writeToCharAwait().
 *
 * Key design:
 * - Urgent channel drains first (for time-sensitive commands like call notifications)
 * - processRequest subscribes to parsedFlow BEFORE writing, so a fast watch
 *   response is never dropped regardless of how quickly the watch replies.
 * - Each MTU chunk gets a dedicated write-completion confirmation before the next
 *   is sent (writeChunked), enforcing serial delivery.
 */
class BleWriteQueue(
    private val scope: CoroutineScope,
    private val parsedFlow: SharedFlow<Packet>,
) {
    private val urgentChannel = Channel<BleRequest>(capacity = 16)
    private val normalChannel = Channel<BleRequest>(capacity = 64)

    private val gattRef = AtomicReference<BluetoothGatt?>(null)

    // Receives write-completion callbacks for command characteristics (0x8001/0x8003).
    // OTA completions go to BleManager.otaWriteCompleteChannel — separate to avoid races.
    private val commandWriteCompleteChannel = Channel<Int>(capacity = 1)

    private var loopJob: Job? = null

    fun attach(gatt: BluetoothGatt) { gattRef.set(gatt) }

    fun detach() {
        gattRef.set(null)
        loopJob?.cancel()
        loopJob = null
    }

    /** Called from BleGattCallback.onCharacteristicWrite for command chars. */
    fun onCommandWriteComplete(status: Int) { commandWriteCompleteChannel.trySend(status) }

    fun start() {
        loopJob = scope.launch {
            while (true) {
                val req = select<BleRequest> {
                    urgentChannel.onReceive { it }
                    normalChannel.onReceive { it }
                }
                processRequest(req)
            }
        }
    }

    suspend fun enqueue(req: BleRequest): Packet {
        Log.d(TAG, "enqueue cmd=${req.expectedCmd} urgent=${req.urgent}")
        if (req.urgent) urgentChannel.send(req) else normalChannel.send(req)
        return req.deferred.await()
    }

    private suspend fun processRequest(req: BleRequest) {
        Log.d(TAG, "→ process cmd=${req.expectedCmd} urgent=${req.urgent} bytes=${req.bytes.toHex()}")
        val gatt = gattRef.get() ?: run {
            Log.e(TAG, "processRequest: GATT not attached, failing ${req.expectedCmd}")
            req.deferred.completeExceptionally(IllegalStateException("GATT not attached"))
            return
        }
        try {
            withTimeout(req.timeoutMs) {
                coroutineScope {
                    // Subscribe to the response flow BEFORE writing.
                    // SharedFlow has no replay — if the watch responds before we call .first(),
                    // the packet would be dropped. Starting the async collector first
                    // guarantees we capture even an immediate response.
                    val responseDeferred = async {
                        parsedFlow.filter { pkt -> matchesResponse(pkt, req) }.first()
                    }

                    // Write all MTU chunks serially, awaiting onCharacteristicWrite between each.
                    writeChunked(gatt, req)

                    // After all chunks written to 8001, send the [0x03] trigger to char 8002.
                    // Source: AppsCommDevice.smali send03ToDevice — this tells the watch to emit its response.
                    // Only for 8001 writes (not 8003); matches isSend03=true default in source app.
                    if (req.charUuid == BleConstants.CHAR_WRITE_8001) {
                        writeTrigger(gatt)
                    }

                    // Await the protocol-level response.
                    val response = responseDeferred.await()
                    Log.d(TAG, "← ${req.expectedCmd} response action=${response.action} payload[${response.payload.size}]=${response.payload.toHex()}")
                    req.deferred.complete(response)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Timeout (${req.timeoutMs}ms) waiting for response to ${req.expectedCmd}")
            req.deferred.completeExceptionally(BleTimeoutException(req.expectedCmd))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing ${req.expectedCmd}: ${e.message}")
            req.deferred.completeExceptionally(e)
        }
    }

    // CHECK commands: watch echoes the same command code with CHECK_RESPONSE action.
    // SET  commands: watch replies with RESPONSE(0x01) + SET_RESPONSE, payload[0] = original cmd.
    // Confirmed from logcat: DATETIME SET → 6F 01 81 02 00 04 01 8F
    private fun matchesResponse(pkt: Packet, req: BleRequest): Boolean {
        if (pkt.action == Action.CHECK_RESPONSE) {
            return pkt.cmdCode == req.expectedCmd
        }
        if (pkt.action == Action.SET_RESPONSE) {
            if (pkt.cmdCode == req.expectedCmd) return true
            return pkt.cmdCode == CommandCode.RESPONSE &&
                    pkt.payload.getOrNull(0) == req.expectedCmd.byte
        }
        return false
    }

    // Source: AppsCommDevice.smali send03ToDevice — writes [0x03] to char 8002 to trigger watch response.
    // Fire-and-forget: no callback wait (8002 is a notify char; write completion is ignored).
    private fun writeTrigger(gatt: BluetoothGatt) {
        val char = gatt.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == BleConstants.CHAR_NOTIFY_8002 }
            ?: run { Log.w(TAG, "writeTrigger: char 8002 not found"); return }
        @Suppress("DEPRECATION")
        char.value = byteArrayOf(0x03)
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(char)
        Log.d(TAG, "→ trigger [03] → 8002")
    }

    /** Sends bytes in MTU chunks, awaiting onCharacteristicWrite between each. */
    private suspend fun writeChunked(gatt: BluetoothGatt, req: BleRequest) {
        val char = gatt.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == req.charUuid }
            ?: throw IllegalStateException("Characteristic ${req.charUuid} not found in GATT table")

        // Set write type based on characteristic: 8003 uses WRITE_NO_RESPONSE (source: AppsCommDevice.smali:885)
        @Suppress("DEPRECATION")
        if (req.charUuid == BleConstants.CHAR_WRITE_8003) {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val chunks = req.bytes.chunkedByMtu(BleConstants.MTU_DEFAULT)
        chunks.forEachIndexed { idx, chunk ->
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            val status = commandWriteCompleteChannel.receive()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "writeChunked FAILED chunk ${idx + 1}/${chunks.size} for ${req.expectedCmd} status=0x${"%02X".format(status)}")
                throw GattWriteException("Write failed: status=0x${"%02X".format(status)} on ${req.charUuid}")
            }
        }
    }
}

private fun ByteArray.chunkedByMtu(mtu: Int): List<ByteArray> {
    val result = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < this.size) {
        result.add(copyOfRange(offset, minOf(offset + mtu, this.size)))
        offset += mtu
    }
    return result
}

private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
