package com.normplus.protocol.ota

import com.normplus.protocol.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Apollo DFU (service 0x1530), as this firmware answers it.
 *
 * Verified against the watch's own OTA responder (`ew_mod_ota_protocol.c`, handler at
 * 0x0003B798) in the watch emulator: a full update of the unmodified
 * `Picture_P03B_NORM2_0.4.bin` goes through, the partition comes out byte-identical and the
 * watch boots identically after it (tools/watchemu/tests/test_ota.py, README "Rehearsing an
 * OTA"). The reference client that passed is tools/watchemu/normwatch/fw/phone.py; this is
 * the same sequence, byte for byte.
 *
 * - Control commands go to 0x1531 and image data to 0x1532, both **write without response**
 *   (the only write either characteristic has). Every reply is a notification on 0x1531:
 *   `[command][status]...`, status 0x01 meaning yes (`OtaApolloCommand.parse`).
 * - The data is stop-and-wait in 200-byte pieces, each answered with the running count.
 */
object ApolloOta {
    /** Update types: `cn.appscomm.bluetooth.ota.OtaApolloCommand` (UPDATE_TYPE_*). */
    const val TYPE_APOLLO: Byte = 1
    const val TYPE_TOUCH: Byte = 2
    const val TYPE_HEART_RATE: Byte = 3
    /** The resource partition (NAND 0x0C780000): UPDATE_TYPE_PICTURE_LANGUAGE. */
    const val TYPE_PICTURE_LANGUAGE: Byte = 4
    /** UPDATE_TYPE_GPS. This watch has no GPS, and its SET handler refuses it. */
    const val TYPE_GPS: Byte = 6

    /**
     * The types the firmware's SET handler (0x0003B8A8) accepts: it keeps an
     * address/length/CRC slot for each of 1-4 and answers anything else `02 00` -- including
     * the 8 that `cn.appscomm.ota`'s getUpdateType gives `Picture_*.bin`.
     */
    val ACCEPTED_TYPES: Set<Byte> = setOf(TYPE_APOLLO, TYPE_TOUCH, TYPE_HEART_RATE, TYPE_PICTURE_LANGUAGE)

    const val CMD_INIT: Byte = 0x01
    const val CMD_SET: Byte = 0x02
    const val CMD_DATA: Byte = 0x03
    const val CMD_CRC: Byte = 0x04
    const val CMD_REBOOT: Byte = 0x05
    const val STATUS_OK: Byte = 0x01

    /** NOTE_10_BLUETOOTH_PARAM. The firmware does not answer it; the app waits 500 ms. */
    val BT_PARAM = byteArrayOf(0x10, 0x02)
    const val BT_PARAM_WAIT_MS = 500L

    /** The last byte of the SET header (PACKAGE_COUNT) the firmware accepted. */
    const val PACKAGE_COUNT: Byte = 0x0A

    /** A NAND page: each is sent as ten 200-byte pieces and a 48 (addMiddleCommand). */
    const val PAGE_SIZE = 0x800
    const val PIECE_SIZE = 200

    /**
     * Bytes per write: 0x80 for the resource type, 0x14 otherwise (OtaApolloCommand.create).
     * 128-byte writes need an ATT MTU of at least 131.
     */
    fun writeSize(type: Byte): Int = if (type == TYPE_PICTURE_LANGUAGE) 0x80 else 0x14

    /**
     * The update type for a file, by name, as `cn.appscomm.bluetooth.ota`'s getUpdateType
     * decides it: "telink" or "apollo" 1, "touchpanel" 2, "heartrate" 3, "gps" 6, and
     * anything else -- `Picture_*.bin`, "Language" -- 4.
     */
    fun updateTypeFor(fileName: String): Byte {
        val name = fileName.lowercase()
        return when {
            "telink" in name || "apollo" in name -> TYPE_APOLLO
            "touchpanel" in name -> TYPE_TOUCH
            "heartrate" in name -> TYPE_HEART_RATE
            "gps" in name -> TYPE_GPS
            else -> TYPE_PICTURE_LANGUAGE
        }
    }

    /**
     * OtaUtil.getApolloCrcCheck: CRC-16/CCITT, init 0xFFFF, as `[lo, hi, 0, 0]`. The
     * firmware's CRC step accepts it (`04 01`).
     */
    fun crc(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (byte in data) {
            crc = ((crc shl 8) or (crc ushr 8)) and 0xFFFF
            crc = crc xor (byte.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte(), 0, 0)
    }

    /** NOTE_01_INIT: `[01][content length LE32]`. The firmware wants exactly five bytes. */
    fun init(content: ByteArray): ByteArray = byteArrayOf(CMD_INIT) + le32(content.size)

    /** NOTE_02_SET, 15 bytes: `[02][type][address 4][length LE32][crc 4][package count]`. */
    fun setHeader(type: Byte, address: ByteArray, content: ByteArray): ByteArray {
        require(address.size == 4) { "address must be 4 bytes" }
        return byteArrayOf(CMD_SET, type) + address + le32(content.size) + crc(content) +
            byteArrayOf(PACKAGE_COUNT)
    }

    /**
     * The data, cut the way addMiddleCommand cuts it: each 2048-byte page in ten 200-byte
     * pieces and a 48-byte one, the remainder in 200s. The watch answers every piece.
     */
    fun pieces(content: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val pages = content.size / PAGE_SIZE
        var at = 0
        repeat(pages) {
            repeat(10) { out += content.copyOfRange(at, at + PIECE_SIZE); at += PIECE_SIZE }
            val rest = PAGE_SIZE - 10 * PIECE_SIZE
            out += content.copyOfRange(at, at + rest); at += rest
        }
        while (at < content.size) {
            val end = minOf(at + PIECE_SIZE, content.size)
            out += content.copyOfRange(at, end); at = end
        }
        return out
    }

    internal fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(), ((value ushr 24) and 0xFF).toByte(),
    )

    internal fun readLe32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}

/** A typed OTA failure: caught by the ViewModel and shown, never left to crash the app. */
class OtaException(message: String) : Exception(message)

/** An update file: the first four bytes are the target address, the rest is the content. */
class OtaImage(val address: ByteArray, val content: ByteArray) {
    companion object {
        /**
         * Splits a `.bin` the way the app does. Refuses a Telink image: the vendor's public
         * OTA server hands out Norm 1 firmware for a Telink SoC (`KNLT` at +0x08, "Norm 1#"),
         * which must never reach this watch (docs/firmware.md section 8).
         */
        fun parse(file: ByteArray): OtaImage {
            if (file.size < 5) throw OtaException("Firmware file too small (${file.size} bytes)")
            if (file.size >= 12 && file.copyOfRange(8, 12).contentEquals("KNLT".toByteArray())) {
                throw OtaException("This is a Telink (Norm 1) image, not Norm 2 firmware")
            }
            return OtaImage(file.copyOf(4), file.copyOfRange(4, file.size))
        }
    }
}

/** What an OTA session needs from the BLE layer. */
interface OtaTransport {
    /** `UPGRADE_MODE` (0x0E) SET `[00]` on 8001; returns once the watch has acknowledged it. */
    suspend fun enterUpgradeMode()

    /**
     * Get ready for the DFU exchange -- listen to 0x1531, raise the MTU -- and return the
     * largest write the link now allows.
     */
    suspend fun openDfu(): Int

    /** A control command to 0x1531, write without response. */
    suspend fun writeControl(bytes: ByteArray)

    /** Image data to 0x1532, write without response. */
    suspend fun writeData(bytes: ByteArray)

    /** The next notification from 0x1531, or null after [timeoutMs]. */
    suspend fun nextReply(timeoutMs: Long): ByteArray?

    /** Forget replies nobody has read. */
    fun clearReplies()

    /** Stop listening; called however the session ends. */
    fun close()
}

/**
 * One update, driven the way the companion app drives it and the firmware answers it.
 * Fails with [OtaException] the moment the watch says no, naming what it refused.
 */
class ApolloOtaSession(
    private val transport: OtaTransport,
    private val log: Logger = Logger.instance,
    private val replyTimeoutMs: Long = 5_000L,
    private val rebootTimeoutMs: Long = 10_000L,
) {
    /**
     * Sends [image] as update [type].
     *
     * A main-MCU update (type 1) is refused unless [allowMcu]: the code that receives an
     * update is the code it replaces, so an image that does not boot leaves no way back
     * without SWD (card #14). Nothing in the app passes it.
     */
    fun flash(image: OtaImage, type: Byte, allowMcu: Boolean = false): Flow<OtaProgress> = flow {
        if (type !in ApolloOta.ACCEPTED_TYPES) {
            throw OtaException("Update type $type is not one this watch accepts (1-4)")
        }
        if (type == ApolloOta.TYPE_APOLLO && !allowMcu) {
            throw OtaException("Main-firmware updates are disabled: there is no recovery path " +
                "if the new image does not boot")
        }
        val content = image.content
        log.i(TAG, "OTA: type $type, address ${image.address.hex()}, ${content.size} bytes")

        emit(OtaProgress(OtaStep.UPGRADE_MODE))
        transport.enterUpgradeMode()
        val maxWrite = transport.openDfu()
        try {
            val writeSize = ApolloOta.writeSize(type)
            if (maxWrite < writeSize) {
                throw OtaException("The link allows $maxWrite-byte writes; this update needs $writeSize")
            }

            emit(OtaProgress(OtaStep.BT_PARAM))
            transport.writeControl(ApolloOta.BT_PARAM)
            delay(ApolloOta.BT_PARAM_WAIT_MS)
            transport.clearReplies()

            emit(OtaProgress(OtaStep.INIT))
            command(ApolloOta.init(content), "INIT")

            emit(OtaProgress(OtaStep.SET_HEADER))
            command(ApolloOta.setHeader(type, image.address, content), "SET (update type $type)")

            val pieces = ApolloOta.pieces(content)
            var sent = 0
            for ((i, piece) in pieces.withIndex()) {
                var at = 0
                while (at < piece.size) {
                    val end = minOf(at + writeSize, piece.size)
                    transport.writeData(piece.copyOfRange(at, end))
                    at = end
                }
                sent += piece.size
                val reply = transport.nextReply(replyTimeoutMs)
                    ?: throw OtaException("No answer to data piece ${i + 1} of ${pieces.size}")
                if (reply.size < 7 || reply[0] != ApolloOta.CMD_DATA || reply[1] != ApolloOta.STATUS_OK ||
                    ApolloOta.readLe32(reply, 3) != sent
                ) {
                    throw OtaException("Data piece ${i + 1} of ${pieces.size} was refused " +
                        "(expected $sent bytes, got ${reply.hex()})")
                }
                emit(OtaProgress(OtaStep.DATA_STREAM, pieces.size, i + 1))
            }

            emit(OtaProgress(OtaStep.CRC_VERIFY, pieces.size, pieces.size))
            command(byteArrayOf(ApolloOta.CMD_CRC), "CRC")

            emit(OtaProgress(OtaStep.REBOOT, pieces.size, pieces.size))
            command(byteArrayOf(ApolloOta.CMD_REBOOT), "REBOOT", rebootTimeoutMs)

            log.i(TAG, "OTA: done, ${pieces.size} pieces")
            emit(OtaProgress(OtaStep.DONE, pieces.size, pieces.size))
        } finally {
            transport.close()
        }
    }

    /** A control command, and its `[command][01]` reply. */
    private suspend fun command(bytes: ByteArray, what: String, timeoutMs: Long = replyTimeoutMs) {
        transport.clearReplies()
        transport.writeControl(bytes)
        val reply = transport.nextReply(timeoutMs)
            ?: throw OtaException("The watch did not answer $what")
        if (reply.size < 2 || reply[0] != bytes[0] || reply[1] != ApolloOta.STATUS_OK) {
            throw OtaException("The watch refused $what (${reply.hex()})")
        }
        log.d(TAG, "OTA: $what -> ${reply.hex()}")
    }

    private companion object {
        const val TAG = "ApolloOta"
        fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
    }
}
