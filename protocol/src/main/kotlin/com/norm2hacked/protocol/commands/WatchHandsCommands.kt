package com.norm2hacked.protocol.commands

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.PacketBuilder
import java.time.Instant
import java.time.ZoneId

// ── Hybrid-watch hand calibration ──────────────────────────────────────────────
//
// The physical hands can drift out of alignment (battery pull, motor skip). Calibration moves
// each hand to a known 12:00 reference, then a normal DATETIME set re-syncs the watch to the real
// time. Two commands drive the hands (source: WatchMoveOne.smali / WatchMoveKeep.smali, decoded
// from the original app's ManualTimeFragment flow):
//
//   WatchMoveOne  (cmd 0xB6, SET): [mode][dir][amountLo][amountHi]   — one nudge
//   WatchMoveKeep (cmd 0xB8, SET): [mode][dir][action]               — continuous / unlock / lock
//
//   mode : 0=hour, 1=minute, 2=second     dir : 1=clockwise/increase, 0=counter-cw/decrease
//   amount (WatchMoveOne) : device-specific LE16 step size (getManualTimeMoveAngle)
//   action (WatchMoveKeep): 0=STOP, 1=START(continuous), 2=UNLOCK, 3=LOCK

/** Which physical hand a move command addresses. */
enum class HandMode(val code: Int) {
    HOUR(0),
    MINUTE(1),
    SECOND(2),
}

/** WatchMoveKeep sub-command (payload byte 2). */
enum class KeepAction(val code: Int) {
    STOP(0),    // stop a continuous move
    START(1),   // start moving continuously in [dir]
    UNLOCK(2),  // release the hands so they can be moved (sent on entering calibration)
    LOCK(3),    // lock the hands in place (sent on save / leaving calibration)
}

object WatchMoveCommand {

    private fun dirByte(clockwise: Boolean): Byte = if (clockwise) 1 else 0

    /** WatchMoveOne SET payload: `[mode][dir][amountLo][amountHi]` (amount little-endian uint16). */
    fun moveOnePayload(mode: HandMode, clockwise: Boolean, amount: Int): ByteArray = byteArrayOf(
        mode.code.toByte(),
        dirByte(clockwise),
        (amount and 0xFF).toByte(),
        ((amount shr 8) and 0xFF).toByte(),
    )

    fun buildMoveOne(mode: HandMode, clockwise: Boolean, amount: Int): ByteArray =
        PacketBuilder.build(CommandCode.WATCH_MOVE_ONE, Action.SET, moveOnePayload(mode, clockwise, amount))

    /** WatchMoveKeep SET payload: `[mode][dir][action]`. `dir` only matters for [KeepAction.START]. */
    fun keepPayload(mode: HandMode, clockwise: Boolean, action: KeepAction): ByteArray = byteArrayOf(
        mode.code.toByte(),
        dirByte(clockwise),
        action.code.toByte(),
    )

    fun buildKeep(mode: HandMode, clockwise: Boolean, action: KeepAction): ByteArray =
        PacketBuilder.build(CommandCode.WATCH_MOVE_KEEP, Action.SET, keepPayload(mode, clockwise, action))
}

// ── Hand movement speed ────────────────────────────────────────────────────────
// Source: BluetoothCommandConstant.smali TRAN_SPEED_SLOW=1, NORMAL=2, FAST=3.

object TranSpeedCommand {
    const val SLOW: Int = 1
    const val NORMAL: Int = 2
    const val FAST: Int = 3

    fun setPayload(speed: Int): ByteArray = byteArrayOf(speed.toByte())

    fun buildSet(speed: Int): ByteArray =
        PacketBuilder.build(CommandCode.TRAN_SPEED, Action.SET, setPayload(speed))
}

// ── Calibration save (DATETIME with the re-home flag) ──────────────────────────
//
// After the hands are aligned to 12:00 we set the time AND tell the watch to drive the hands to it.
// That's the ordinary DATETIME (0x04) SET with byte[8]=1 — see DateTimeCommand.setPayload for the
// full 12-byte layout; ManualTimeFragment.completeCalibration is the original's caller, and it
// passes setDateTime(y,mo,d,h,mi,s, 0, 1, tz0,tz1,tz2).
// (We previously had the flag at byte[11] and tz at [7..9] — the watch then read the flag as a tz
// value and never re-homed.)

object CalibrationSaveCommand {

    /**
     * DATETIME SET payload with the re-home flag defaulted on — the calibration-flavoured entry
     * point to [DateTimeCommand.setPayload] (same 12 bytes; byte[8]=1 tells the watch to drive the
     * hands to the set time from their current 12:00 position).
     */
    fun dateTimePayload(
        instant: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        reHome: Boolean = true,
    ): ByteArray = DateTimeCommand.setPayload(instant, zone, reHome)

    fun buildSet(instant: Instant = Instant.now()): ByteArray =
        PacketBuilder.build(CommandCode.DATETIME, Action.SET, dateTimePayload(instant))
}
