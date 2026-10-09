package com.normplus.domain.usecase

import android.util.Log
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.Packet
import com.normplus.protocol.WatchTransport
import com.normplus.protocol.commands.BindEndCommand
import com.normplus.protocol.commands.BindStartCommand
import com.normplus.protocol.commands.DateTimeCommand
import javax.inject.Inject

private const val TAG = "BindWatchUseCase"

/** What the bind handshake found or did. */
sealed class BindResult {
    /** The watch was already initialised — nothing was sent beyond the check. */
    data object AlreadyBound : BindResult()

    /** bindStart → setDateTime → bindEnd all acknowledged; the watch shows "Pairing Success". */
    data object Bound : BindResult()

    /** A step was refused or unanswered; [message] carries the hex so the log is enough to act on. */
    data class Failed(val step: String, val message: String) : BindResult()
}

/**
 * The first-run bind handshake the original app runs after a QR scan (`BindDevice.start6F`,
 * lines 298–306 of the Java): bindStart → setDateTime → bindEnd. It is what takes a fresh
 * watch off its "Select a Language" / QR screen and onto the watch face. Bonding alone does not
 * do it, and neither does a connection: the watch sits in setup until this arrives.
 *
 * The bytes are pinned by `BindCommandTest`; the behaviour by the watch emulator's
 * `tests/test_ble_end_to_end.py`, which runs this exact sequence against the real firmware:
 *
 *  - bindStart (0x93 SET `[02]`) opens the watch's pairing dialog (`ui_notify_pairing_dlg.c`),
 *    which waits **300 frames — 15 s — for bindEnd** and otherwise shows "Pairing Failed".
 *    The watch acknowledges bindStart at once (`6F 01 81 02 00 93 00`) and the original app
 *    allows it 30 s, so the queue never blocks on it.
 *  - setDateTime is the ordinary DATETIME SET, re-home flag off.
 *  - bindEnd (0x94 SET `[01]`) is what the dialog is waiting for: "Pairing Success", then the
 *    face. `checkInit` (0x94 CHECK) reads 1 from then on.
 *
 * [bind] runs `checkInit` first and does nothing on an initialised watch: the original only
 * binds from its pairing screens too, and on every other connect just checks the flag
 * (`GlobalNewService$4`: a 0 there un-pairs the phone). Re-sending bindStart to a bound watch
 * would pop its pairing dialog for no reason.
 *
 * Failures are returned, not thrown: the connection stays up either way, and the pairing screen
 * decides what to tell the user.
 */
class BindWatchUseCase @Inject constructor(
    private val transport: WatchTransport,
) {
    suspend fun bind(): BindResult = try {
        val init = step("checkInit") {
            transport.sendAndAwait(BindEndCommand.CMD, Action.CHECK, BindEndCommand.queryPayload())
        }
        if (BindEndCommand.parseInitialised(init)) {
            Log.i(TAG, "watch already initialised, not binding again")
            BindResult.AlreadyBound
        } else {
            step("bindStart", timeoutMs = BindStartCommand.REPLY_TIMEOUT_MS) {
                transport.sendAndAwait(
                    BindStartCommand.CMD, Action.SET, BindStartCommand.setPayload(BindStartCommand.MODE_QR_CODE),
                    timeoutMs = BindStartCommand.REPLY_TIMEOUT_MS,
                )
            }
            step("setDateTime") {
                transport.sendAndAwait(CommandCode.DATETIME, Action.SET, DateTimeCommand.setPayload())
            }
            step("bindEnd") {
                transport.sendAndAwait(BindEndCommand.CMD, Action.SET, BindEndCommand.setPayload())
            }
            Log.i(TAG, "bound: the watch should be showing Pairing Success")
            BindResult.Bound
        }
    } catch (e: StepFailed) {
        BindResult.Failed(e.step, e.message ?: "")
    }

    private class StepFailed(val step: String, message: String) : Exception(message)

    /** One command; the reply, or [StepFailed] if there was none or the watch declined. */
    private suspend fun step(name: String, timeoutMs: Long = 10_000L, send: suspend () -> Packet): Packet {
        val packet = try {
            send()
        } catch (e: Exception) {
            Log.w(TAG, "$name: no reply (${e.message})")
            throw StepFailed(name, "no reply within ${timeoutMs}ms: ${e.message}")
        }
        val hex = packet.payload.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "$name: ${packet.cmdCode} ${packet.action} [$hex]")
        // SET acks come back as RESPONSE(0x01) with payload [cmd][status]; CHECKs echo the
        // command with its own payload, which has no status byte to test.
        if (packet.action == Action.SET_RESPONSE && packet.cmdCode == CommandCode.RESPONSE) {
            val status = packet.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            if (status != 0) {
                Log.w(TAG, "$name: watch declined, status $status")
                throw StepFailed(name, "watch declined (status $status, reply [$hex])")
            }
        }
        return packet
    }
}
