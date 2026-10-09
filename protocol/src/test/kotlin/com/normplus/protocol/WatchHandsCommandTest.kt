package com.normplus.protocol

import com.normplus.protocol.commands.CalibrationSaveCommand
import com.normplus.protocol.commands.HandMode
import com.normplus.protocol.commands.KeepAction
import com.normplus.protocol.commands.TranSpeedCommand
import com.normplus.protocol.commands.WatchMoveCommand
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-exact tests for the hand-calibration commands (WatchMoveOne 0xB6 / WatchMoveKeep 0xB8 /
 * TranSpeed 0x16). Frame = [0x6F][cmd][action][lenLo][lenHi][payload...][0x8F].
 */
class WatchHandsCommandTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `WatchMoveOne clockwise nudge frames mode, dir=1 and LE16 amount`() {
        // minute hand, clockwise, amount = 300 (0x012C) → LE [2C, 01]
        val frame = WatchMoveCommand.buildMoveOne(HandMode.MINUTE, clockwise = true, amount = 0x012C)
        val expected = byteArrayOf(
            0x6F, 0xB6.toByte(), 0x71, // start, WATCH_MOVE_ONE, SET
            0x04, 0x00,                // contentLen = 4
            0x01,                      // mode = minute
            0x01,                      // dir = clockwise
            0x2C, 0x01,                // amount = 300, little-endian
            0x8F.toByte(),
        )
        assertEquals(hex(expected), hex(frame))
    }

    @Test
    fun `WatchMoveOne counter-clockwise sets dir=0 and hour mode`() {
        val frame = WatchMoveCommand.buildMoveOne(HandMode.HOUR, clockwise = false, amount = 1)
        val expected = byteArrayOf(
            0x6F, 0xB6.toByte(), 0x71,
            0x04, 0x00,
            0x00,       // mode = hour
            0x00,       // dir = counter-clockwise
            0x01, 0x00, // amount = 1
            0x8F.toByte(),
        )
        assertEquals(hex(expected), hex(frame))
    }

    @Test
    fun `WatchMoveKeep encodes mode, dir and action byte`() {
        // UNLOCK (2), START (1), STOP (0), LOCK (3)
        assertEquals(
            hex(byteArrayOf(0x6F, 0xB8.toByte(), 0x71, 0x03, 0x00, 0x01, 0x01, 0x02, 0x8F.toByte())),
            hex(WatchMoveCommand.buildKeep(HandMode.MINUTE, clockwise = true, KeepAction.UNLOCK)),
        )
        assertEquals(
            hex(byteArrayOf(0x6F, 0xB8.toByte(), 0x71, 0x03, 0x00, 0x00, 0x00, 0x01, 0x8F.toByte())),
            hex(WatchMoveCommand.buildKeep(HandMode.HOUR, clockwise = false, KeepAction.START)),
        )
        assertEquals(
            hex(byteArrayOf(0x6F, 0xB8.toByte(), 0x71, 0x03, 0x00, 0x02, 0x01, 0x00, 0x8F.toByte())),
            hex(WatchMoveCommand.buildKeep(HandMode.SECOND, clockwise = true, KeepAction.STOP)),
        )
        assertEquals(
            hex(byteArrayOf(0x6F, 0xB8.toByte(), 0x71, 0x03, 0x00, 0x01, 0x01, 0x03, 0x8F.toByte())),
            hex(WatchMoveCommand.buildKeep(HandMode.MINUTE, clockwise = true, KeepAction.LOCK)),
        )
    }

    @Test
    fun `keep and moveOne round-trip through the deframer`() {
        val keep = PacketDeframer().feed(
            WatchMoveCommand.buildKeep(HandMode.MINUTE, clockwise = true, KeepAction.UNLOCK),
        ).single()
        assertEquals(CommandCode.WATCH_MOVE_KEEP, keep.cmdCode)
        assertEquals(Action.SET, keep.action)

        val one = PacketDeframer().feed(
            WatchMoveCommand.buildMoveOne(HandMode.HOUR, clockwise = false, amount = 5),
        ).single()
        assertEquals(CommandCode.WATCH_MOVE_ONE, one.cmdCode)
    }

    @Test
    fun `calibration save payload has flag at byte 8 and tz triplet at 9-11`() {
        // 2026-01-02 03:04:05 at UTC+02:00 → byte7=0, byte8=flag(1), tz sign=1, tzH=2, tzM=0.
        val instant = Instant.parse("2026-01-02T01:04:05Z") // 03:04:05 local at +02:00
        val payload = CalibrationSaveCommand.dateTimePayload(instant, ZoneId.of("+02:00"))
        val expected = byteArrayOf(
            0xEA.toByte(), 0x07, // year 2026 little-endian (0x07EA)
            0x01,                // month = January
            0x02,                // day
            0x03,                // hour (local)
            0x04,                // minute
            0x05,                // second
            0x00,                // byte7: reserved
            0x01,                // byte8: re-home flag
            0x01,                // byte9:  tz sign = +
            0x02,                // byte10: tz hours
            0x00,                // byte11: tz minutes
        )
        assertEquals(hex(expected), hex(payload))
        assertEquals(12, payload.size)
    }

    @Test
    fun `plain clock set clears the re-home flag (byte 8 = 0)`() {
        val payload = CalibrationSaveCommand.dateTimePayload(
            Instant.parse("2026-01-02T01:04:05Z"), ZoneId.of("+02:00"), reHome = false,
        )
        assertEquals(0x00.toByte(), payload[8]) // no re-home
    }

    @Test
    fun `negative timezone sets sign byte to 0`() {
        val payload = CalibrationSaveCommand.dateTimePayload(Instant.parse("2026-01-02T12:00:00Z"), ZoneId.of("-05:00"))
        assertEquals(0x01.toByte(), payload[8]) // still re-homes
        assertEquals(0x00.toByte(), payload[9]) // sign = negative
        assertEquals(0x05.toByte(), payload[10]) // 5 hours
    }

    @Test
    fun `TranSpeed FAST frames a single speed byte`() {
        val frame = TranSpeedCommand.buildSet(TranSpeedCommand.FAST)
        val expected = byteArrayOf(0x6F, 0x16, 0x71, 0x01, 0x00, 0x03, 0x8F.toByte())
        assertEquals(hex(expected), hex(frame))
    }
}
