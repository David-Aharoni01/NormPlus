package com.norm2hacked.protocol.commands

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.PacketBuilder
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import kotlin.math.abs

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
// After the hands are aligned to 12:00 we set the time AND tell the watch to drive the hands to
// it. The DATETIME (0x04) SET body is 12 bytes — byte order verified from DateTime.smali +
// ManualTimeFragment.completeCalibration (which calls setDateTime(y,mo,d,h,mi,s, 0, 1, tz0,tz1,tz2)):
//   [yearLo][yearHi][month][day][hour][min][sec][0][homeFlag][tzSign][tzHour][tzMin]
//                                              (7)  (8)        (9)     (10)    (11)
// byte[8] is 0 for a normal clock sync but **1 for calibration** — it re-homes the hands to the
// set time from their current 12:00 position. tz triplet = getTimeZone4City() (sign 1=+/0=-, hr, min).
// (We previously had the flag at byte[11] and tz at [7..9] — the watch then read the flag as a tz
// value and never re-homed.)

object CalibrationSaveCommand {

    /**
     * DATETIME SET payload. [reHome]=true sets byte[8]=1 ("set this time and drive the hands to it
     * from their current 12:00 position"); [reHome]=false is a plain clock set (byte[8]=0).
     */
    fun dateTimePayload(
        instant: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        reHome: Boolean = true,
    ): ByteArray {
        val cal = Calendar.getInstance().also { it.timeInMillis = instant.toEpochMilli() }
        val year = cal.get(Calendar.YEAR)
        val offsetMinutes = zone.rules.getOffset(instant).totalSeconds / 60
        val sign = if (offsetMinutes >= 0) 1 else 0
        val absMinutes = abs(offsetMinutes)
        return byteArrayOf(
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            0,                                 // byte7: reserved (0 in both normal + calibration)
            if (reHome) 1 else 0,              // byte8: re-home flag
            sign.toByte(),                     // byte9:  tz sign (1 = +, 0 = -)
            (absMinutes / 60).toByte(),        // byte10: tz offset hours
            (absMinutes % 60).toByte(),        // byte11: tz offset minutes
        )
    }

    fun buildSet(instant: Instant = Instant.now()): ByteArray =
        PacketBuilder.build(CommandCode.DATETIME, Action.SET, dateTimePayload(instant))
}
