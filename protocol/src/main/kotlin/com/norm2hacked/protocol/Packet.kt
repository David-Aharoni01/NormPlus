package com.norm2hacked.protocol

import com.norm2hacked.ble.BleConstants
import java.io.ByteArrayOutputStream

private const val TAG = "PacketDeframer"

data class Packet(
    val cmdCode: CommandCode,
    val action: Action,
    val payload: ByteArray
) {
    override fun equals(other: Any?) = other is Packet &&
            cmdCode == other.cmdCode && action == other.action && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * (31 * cmdCode.hashCode() + action.hashCode()) + payload.contentHashCode()
}

object PacketBuilder {
    // Frame: [0x6F][cmdCode][action][lenLo][lenHi][payload...][0x8F]
    // Source: Leaf.smali getSendData() — non-6E branch
    fun build(cmd: CommandCode, action: Action, payload: ByteArray = byteArrayOf()): ByteArray {
        val len = payload.size
        return ByteArray(len + 6).also { buf ->
            buf[0] = BleConstants.FLAG_START
            buf[1] = cmd.byte
            buf[2] = action.byte
            buf[3] = (len and 0xFF).toByte()
            buf[4] = ((len shr 8) and 0xFF).toByte()
            payload.copyInto(buf, destinationOffset = 5)
            buf[len + 5] = BleConstants.FLAG_END
        }
    }
}

// Reassembles BLE frames that span multiple MTU chunks.
//
// Frame: [0x6F][cmd][action][lenLo][lenHi][payload…][0x8F]
//
// PRIMARY rule is length-guided: the declared payload length (header bytes 3-4) sets the frame
// boundary, NOT the first 0x8F — so an 0x8F that happens to appear inside a payload does not end
// the frame early. The trailing 0x8F is validated as a terminator: if a length-complete frame
// does not end in 0x8F, we were mis-synced and resync.
//
// SELF-SYNCHRONIZING: the watch can drop or reorder notifications (observed: a 28-byte sport
// record whose 14-byte continuation was never sent, replaced by an unrelated ack). A naive
// length-guided parser would then swallow following frames as "payload" and corrupt the channel
// indefinitely. To recover, while an in-progress frame is still incomplete we look ahead for a
// later, fully-present, 0x8F-terminated frame with a valid cmd+action; finding one means the
// current frame was truncated, so we drop it and resync there. Because a single notification can
// thus complete more than one frame, feed() returns a LIST of packets (possibly empty).
//
// Source: BluetoothParse.smali — isSingleCommandByDataLen logic
class PacketDeframer {
    // Holds all bytes received but not yet consumed into a completed/discarded frame.
    private val buffer = ByteArrayOutputStream()

    /** Feed raw notification bytes; returns every complete packet recoverable from the buffer. */
    fun feed(chunk: ByteArray): List<Packet> {
        buffer.write(chunk)
        val bytes = buffer.toByteArray()
        val out = mutableListOf<Packet>()
        var pos = 0 // index of the first not-yet-consumed byte

        while (true) {
            val start = indexOfStart(bytes, pos)
            if (start < 0) {
                // No frame-start byte remains — drop everything (nothing parseable).
                pos = bytes.size
                break
            }
            pos = start // drop any garbage before the start byte
            val avail = bytes.size - start

            if (avail < HEADER_SIZE) break // need the full 5-byte header to know the length

            val payloadLen = (bytes[start + 3].toInt() and 0xFF) or ((bytes[start + 4].toInt() and 0xFF) shl 8)
            if (payloadLen > MAX_PAYLOAD_LEN) {
                // Implausible length → this 0x6F isn't a real frame start; skip it and rescan.
                pos = start + 1
                continue
            }
            val frameSize = payloadLen + FRAME_OVERHEAD

            if (avail < frameSize) {
                // Not enough bytes yet. Before waiting, check whether this frame was truncated:
                // is there a later, fully-present, valid frame embedded in the buffer?
                val resync = findValidFrameStart(bytes, start + 1)
                if (resync >= 0) {
                    Logger.instance.w(
                        TAG,
                        "Truncated frame at offset $start (declared len=$payloadLen) — resyncing to valid frame at $resync"
                    )
                    pos = resync
                    continue
                }
                break // genuinely waiting for the rest of this frame
            }

            if (bytes[start + frameSize - 1] == BleConstants.FLAG_END) {
                parse(bytes, start, frameSize)?.let { out.add(it) }
                pos = start + frameSize
            } else {
                // Length-complete but no terminator → this 0x6F was not a real start; resync.
                Logger.instance.w(
                    TAG,
                    "FLAG_END mismatch at offset $start (len=$payloadLen, got 0x${"%02X".format(bytes[start + frameSize - 1])}); resyncing"
                )
                pos = start + 1
            }
        }

        // Retain the unconsumed tail for the next feed().
        val tail = bytes.copyOfRange(pos, bytes.size)
        buffer.reset()
        buffer.write(tail)
        return out
    }

    private fun indexOfStart(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == BleConstants.FLAG_START) return i
            i++
        }
        return -1
    }

    // Scans for a 0x6F at/after [from] that begins a frame fully present in [bytes], terminated by
    // 0x8F at its declared length, with a recognised cmd and action. Returns its index, or -1.
    // The strict validation (length lands exactly on 0x8F + known cmd/action) keeps false positives
    // from random payload bytes vanishingly unlikely.
    private fun findValidFrameStart(bytes: ByteArray, from: Int): Int {
        var i = from
        while (true) {
            val s = indexOfStart(bytes, i)
            if (s < 0) return -1
            val avail = bytes.size - s
            if (avail < HEADER_SIZE) return -1 // can't validate any candidate this far out yet
            val payloadLen = (bytes[s + 3].toInt() and 0xFF) or ((bytes[s + 4].toInt() and 0xFF) shl 8)
            if (payloadLen in 0..MAX_PAYLOAD_LEN) {
                val frameSize = payloadLen + FRAME_OVERHEAD
                if (avail >= frameSize &&
                    bytes[s + frameSize - 1] == BleConstants.FLAG_END &&
                    CommandCode.fromByte(bytes[s + 1]) != null &&
                    Action.fromByte(bytes[s + 2]) != null
                ) {
                    return s
                }
            }
            i = s + 1
        }
    }

    private fun parse(bytes: ByteArray, start: Int, frameSize: Int): Packet? {
        val cmdCode = CommandCode.fromByte(bytes[start + 1]) ?: run {
            Logger.instance.w(TAG, "parse: unknown commandCode=0x${"%02X".format(bytes[start + 1])}")
            return null
        }
        val action = Action.fromByte(bytes[start + 2]) ?: run {
            Logger.instance.w(TAG, "parse: unknown action=0x${"%02X".format(bytes[start + 2])}")
            return null
        }
        val payload = bytes.copyOfRange(start + 5, start + frameSize - 1)
        return Packet(cmdCode, action, payload)
    }

    companion object {
        private const val HEADER_SIZE = 5         // start(1)+cmd(1)+action(1)+len(2)
        private const val FRAME_OVERHEAD = 6      // header(5)+end(1)
        private const val MAX_PAYLOAD_LEN = 512
    }
}
