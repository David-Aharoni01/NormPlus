package com.norm2hacked.protocol.ota

import android.content.Context
import android.net.Uri
import android.util.Log
import com.norm2hacked.ble.BleConstants
import com.norm2hacked.ble.BleManager
import com.norm2hacked.protocol.commands.UpgradeModeCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

class OtaException(message: String) : Exception(message)

private const val TAG = "ApolloOta"

/**
 * Apollo DFU firmware update protocol.
 *
 * Protocol source: OtaApolloCommand.smali, OtaService.smali
 *
 * Bin file format: first 4 bytes = target flash address, remaining bytes = firmware content.
 *
 * The watch drives the state machine via notifications on characteristic 0x1532.
 * Each notification has byte[1] indicating the step the watch is ready to accept next:
 *   0x01 → INIT   (watch processed BT_PARAM, ready for INIT)
 *   0x02 → SET    (watch processed INIT, ready for SET_HEADER and data)
 *   0x03 → DATA   (checkpoint ACK during streaming; byte[2]==0x04 means segment OK)
 *   0x04 → CRC    (watch processed all data, CRC result — we send REBOOT next)
 *   0x05 → DONE   (update complete)
 *
 * All writes to 0x1531 use WRITE_WITH_RESPONSE, so onCharacteristicWrite fires after
 * the watch confirms receipt at the BLE transport level. writeToCharAwait enforces
 * serial delivery: each MTU chunk is confirmed before the next is sent.
 *
 * Data is streamed in PACKAGE_COUNT (10) × MTU-byte chunks between checkpoint ACKs.
 */
@Singleton
class ApolloOtaProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
) {
    enum class UpdateType(val byte: Byte) {
        MCU(0x01), TOUCH_PANEL(0x02), HEART_RATE(0x03), PICTURE(0x08)
    }

    companion object {
        private const val PACKAGE_COUNT = 10  // chunks per checkpoint group (from OtaApolloCommand)
        private const val OTA_RESULT_FAIL: Byte = 0x66.toByte()  // from OtaManager.smali
    }

    // ── Public entry points ───────────────────────────────────────────────────

    /** Flash a user-supplied .bin file selected via the file picker (content:// URI). */
    fun flash(uri: Uri, updateType: UpdateType = UpdateType.MCU): Flow<OtaProgress> = flow {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read firmware file")
        emitAll(flashImpl(bytes, updateType))
    }

    /** Flash a .bin bundled in assets — avoids file:// URI issues on Android 7+. */
    fun flashAsset(assetName: String, updateType: UpdateType = UpdateType.MCU): Flow<OtaProgress> = flow {
        val bytes = runCatching { context.assets.open(assetName).use { it.readBytes() } }
            .getOrElse { throw IllegalArgumentException("Cannot read asset: $assetName") }
        emitAll(flashImpl(bytes, updateType))
    }

    // ── Core protocol implementation ──────────────────────────────────────────

    // channelFlow provides a CoroutineScope and cancels all launched children when the block
    // exits — whether normally, via exception, or via downstream cancellation. This replaces
    // the previous flow{} + coroutineScope{} pattern where the infinite otaFlow collector
    // child prevented coroutineScope from ever returning after DONE was emitted.
    private fun flashImpl(bytes: ByteArray, updateType: UpdateType): Flow<OtaProgress> = channelFlow {
        if (bytes.size < 4) throw IllegalArgumentException("Firmware file too small")

        val address = bytes.copyOf(4)                           // first 4 bytes = flash address
        val content = bytes.copyOfRange(4, bytes.size)         // remainder = firmware content
        val mtu = if (updateType == UpdateType.PICTURE) BleConstants.MTU_WATCHFACE
                  else BleConstants.MTU_DEFAULT

        Log.i(TAG, "OTA start: type=$updateType addr=${address.toHex()} contentSize=${content.size} mtu=$mtu")

        // Drain any stale write-completions from a previous cancelled OTA session before
        // starting fresh. Without this, the first writeToCharAwait of the new session
        // could consume the stale completion and skip its own write confirmation.
        bleManager.drainOtaWriteChannel()

        // Pre-subscribe to OTA notifications BEFORE writing anything so we never miss a response.
        // The collector is launched as a child of channelFlow's scope and is automatically
        // cancelled when the block exits.
        val ackBuf = Channel<ByteArray>(capacity = 32)
        launch { bleManager.otaFlow.collect {
            Log.d(TAG, "← OTA notify step=0x${"%02X".format(it.getOrElse(1) { 0xFF.toByte() })} hex=${it.toHex()}")
            ackBuf.send(it)
        } }

        // Waits for the expected ACK step from the watch.
        // Fails fast on OTA_RESULT_FAIL (0x66) rather than waiting out the full timeout,
        // so firmware rejection surfaces immediately with an actionable error.
        suspend fun awaitStep(step: Byte, timeoutMs: Long = 10_000L): ByteArray =
            withTimeout(timeoutMs) {
                var r: ByteArray
                do {
                    r = ackBuf.receive()
                    if (r.size >= 2 && r[1] == OTA_RESULT_FAIL) {
                        Log.e(TAG, "Watch reported OTA FAIL for step=0x${"%02X".format(step)}: ${r.toHex()}")
                        throw OtaException("Watch reported failure waiting for step 0x${"%02X".format(step)}")
                    }
                } while (r.size < 2 || r[1] != step)
                Log.d(TAG, "  step=0x${"%02X".format(step)} confirmed")
                r
            }

        // ── Step 0: BT_PARAM ──────────────────────────────────────────────────
        // Sent first; watch responds with byte[1]=0x01 when ready for INIT.
        // Source: OtaApolloCommand.addStartCommand / NOTE_10_BLUETOOTH_PARAM
        Log.i(TAG, "OTA step 0: BT_PARAM")
        send(OtaProgress(OtaStep.BT_PARAM))
        bleManager.writeToCharAwait(byteArrayOf(0x10, 0x02), BleConstants.CHAR_APOLLO_1531, mtu)
        delay(BleConstants.OTA_BT_PARAM_DELAY_MS)
        awaitStep(0x01)

        // ── Step 1: INIT ──────────────────────────────────────────────────────
        // Announces total content size; watch responds with byte[1]=0x02 when ready for SET.
        Log.i(TAG, "OTA step 1: INIT contentSize=${content.size}")
        send(OtaProgress(OtaStep.INIT))
        val initPacket = byteArrayOf(0x01) + encodeLE32(content.size)
        bleManager.writeToCharAwait(initPacket, BleConstants.CHAR_APOLLO_1531, mtu)
        awaitStep(0x02)

        // ── Step 2: SET_HEADER (15 bytes) ─────────────────────────────────────
        // Announces address, content length, CRC.
        // After SET_HEADER the watch transitions state internally and sends byte[1]=0x02
        // to signal it is ready to receive data. We MUST wait for this before streaming
        // — sending data while the watch is still in SET state causes corruption.
        Log.i(TAG, "OTA step 2: SET_HEADER")
        send(OtaProgress(OtaStep.SET_HEADER))
        val crc = apolloCrc16(content)
        Log.d(TAG, "  crc=${crc.toHex()} addr=${address.toHex()}")
        val setHeader = buildSetHeader(updateType.byte, address, encodeLE32(content.size), crc)
        bleManager.writeToCharAwait(setHeader, BleConstants.CHAR_APOLLO_1531, mtu)
        awaitStep(0x02)  // wait for "ready for data" confirmation

        // ── Step 3: DATA STREAM ───────────────────────────────────────────────
        // Stream content in MTU-sized chunks. After every PACKAGE_COUNT (10) chunks
        // the watch sends a checkpoint notification (byte[1]=0x03, byte[2]=0x04 = OK).
        // Source: OtaApolloCommand.addMiddleCommand, PACKAGE_COUNT=0x0A
        val chunks = content.chunkedBytes(mtu)
        val totalPackets = chunks.size
        var packetsSent = 0
        Log.i(TAG, "OTA step 3: DATA_STREAM totalChunks=$totalPackets")

        for ((i, chunk) in chunks.withIndex()) {
            bleManager.writeToCharAwait(chunk, BleConstants.CHAR_APOLLO_1531, mtu)
            packetsSent++
            send(OtaProgress(OtaStep.DATA_STREAM, totalPackets, packetsSent))

            val isCheckpoint = (i + 1) % PACKAGE_COUNT == 0
            val isLast = i == totalPackets - 1

            if (isCheckpoint || isLast) {
                Log.d(TAG, "  checkpoint at chunk $packetsSent/$totalPackets")
                val ack = awaitStep(0x03)
                // byte[2] == 0x04 means this segment was received correctly.
                if (ack.size < 3 || ack[2] != 0x04.toByte()) {
                    Log.e(TAG, "  checkpoint FAILED chunk=$packetsSent ack=${ack.toHex()}")
                    send(OtaProgress(OtaStep.FAILED, totalPackets, packetsSent,
                        "Data checkpoint failed at chunk $packetsSent (ack=${ack.toHex()})"))
                    return@channelFlow
                }
                Log.d(TAG, "  checkpoint OK")
            }
        }

        // ── Step 4: CRC_VERIFY ────────────────────────────────────────────────
        // Send the CRC command; watch responds with byte[1]=0x04 confirming it ran CRC.
        // A successful CRC is indicated by the watch then accepting REBOOT (step 5).
        Log.i(TAG, "OTA step 4: CRC_VERIFY")
        send(OtaProgress(OtaStep.CRC_VERIFY, totalPackets, totalPackets))
        bleManager.writeToCharAwait(byteArrayOf(0x04), BleConstants.CHAR_APOLLO_1531, mtu)
        awaitStep(0x04)

        // ── Step 5: REBOOT ────────────────────────────────────────────────────
        // Watch reboots into the new firmware. byte[1]=0x05 signals success.
        Log.i(TAG, "OTA step 5: REBOOT — waiting up to 30s for watch to restart")
        send(OtaProgress(OtaStep.REBOOT, totalPackets, totalPackets))
        bleManager.writeToCharAwait(byteArrayOf(0x05), BleConstants.CHAR_APOLLO_1531, mtu)
        awaitStep(0x05, timeoutMs = 30_000L)  // reboot can take up to ~15 s

        Log.i(TAG, "OTA complete! type=$updateType contentSize=${content.size}")
        send(OtaProgress(OtaStep.DONE, totalPackets, totalPackets))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // 15-byte SET_HEADER: [0x02][updateType][addr[4]][lenLE[4]][crc[4]][PACKAGE_COUNT]
    // Source: OtaApolloCommand.addMiddleCommand
    private fun buildSetHeader(
        updateType: Byte,
        addr: ByteArray,
        len: ByteArray,
        crc: ByteArray,
    ): ByteArray {
        check(addr.size == 4 && len.size == 4 && crc.size == 4)
        val buf = ByteArray(15)
        buf[0] = 0x02
        buf[1] = updateType
        addr.copyInto(buf, 2)
        len.copyInto(buf, 6)
        crc.copyInto(buf, 10)
        buf[14] = PACKAGE_COUNT.toByte()
        return buf
    }

    // CRC-16/CCITT: init=0xFFFF.
    // Source: OtaUtil.smali crc16() — verified to match the smali byte-by-byte.
    // Returns 4 bytes [crc_lo, crc_hi, 0x00, 0x00] (getApolloCrcCheck format).
    private fun apolloCrc16(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (byte in data) {
            crc = ((crc shl 8) or (crc ushr 8)) and 0xFFFF
            crc = crc xor (byte.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc ushr 8) and 0xFF).toByte(),
            0x00, 0x00,
        )
    }

    // Little-endian 32-bit encoding.
    private fun encodeLE32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun ByteArray.chunkedBytes(size: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < this.size) {
            result.add(copyOfRange(offset, minOf(offset + size, this.size)))
            offset += size
        }
        return result
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
