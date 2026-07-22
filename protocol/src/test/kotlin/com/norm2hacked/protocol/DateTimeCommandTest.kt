package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.DateTimeCommand
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTimeCommandTest {

    @Test
    fun `datetime SET encodes the year little-endian`() {
        // Regression for the clock-sync endianness bug: DateTime.smali builds the year with
        // ParseUtil.intToByteArray(year, 2) — the same little-endian encoding used for the frame's
        // 2-byte content length (verified in PacketTest). The year must therefore be [lo, hi].
        // 2026 = 0x07EA → little-endian [0xEA, 0x07]. (The old code emitted big-endian [0x07, 0xEA],
        // which the watch decoded as year 0xEA07 = 59911.)
        val frame = DateTimeCommand.buildSet(Instant.parse("2026-06-15T12:00:00Z"))
        val pkt = PacketDeframer().feed(frame).single()

        assertEquals(CommandCode.DATETIME, pkt.cmdCode)
        assertEquals(Action.SET, pkt.action)

        assertEquals(0xEA.toByte(), pkt.payload[0], "year low byte must come first (little-endian)")
        assertEquals(0x07.toByte(), pkt.payload[1], "year high byte must come second")

        val year = (pkt.payload[0].toInt() and 0xFF) or ((pkt.payload[1].toInt() and 0xFF) shl 8)
        assertEquals(2026, year)
        // Noon UTC on the 15th lands on the 15th/16th but always in month 6, in every time zone.
        assertEquals(6, pkt.payload[2].toInt(), "month must be 1-based (June = 6)")
    }

    @Test
    fun `datetime SET puts 0 at byte 7 and the timezone triplet at 9-11`() {
        // Regression for the byte-order bug: we used to write DAY_OF_WEEK at byte[7] and leave
        // bytes[8..11] zero, dropping the timezone entirely. DateTime.smali lays the 12-byte body
        // out as [yLo][yHi][mo][d][h][mi][s][0][reHome][tzSign][tzHr][tzMin], and
        // SyncBluetoothDataNew.setTimeToDevice supplies (…, s, 0, 0, tz0, tz1, tz2) for a plain sync.
        val payload = DateTimeCommand.setPayload(
            Instant.parse("2026-01-02T01:04:05Z"), ZoneId.of("+02:00"), // → 03:04:05 local
        )
        val expected = byteArrayOf(
            0xEA.toByte(), 0x07, // year 2026, little-endian
            0x01,                // month
            0x02,                // day
            0x03,                // hour (local to the given zone, not the JVM default)
            0x04,                // minute
            0x05,                // second
            0x00,                // byte7: always 0 (was DAY_OF_WEEK)
            0x00,                // byte8: re-home off for a plain clock sync
            0x01,                // byte9:  tz sign = '+'
            0x02,                // byte10: tz hours
            0x00,                // byte11: tz minutes
        )
        assertEquals(
            expected.joinToString(" ") { "%02X".format(it) },
            payload.joinToString(" ") { "%02X".format(it) },
        )
    }

    @Test
    fun `a routine clock sync never re-homes the hands`() {
        // byte[8]=1 makes the watch sweep its physical hands from 12:00; only calibration wants that.
        assertEquals(0x00.toByte(), DateTimeCommand.setPayload()[8])
    }

    @Test
    fun `sub-hour and negative offsets encode as sign plus hours and minutes`() {
        // India = UTC+05:30 → sign 1, 5h, 30m.
        val india = DateTimeCommand.setPayload(Instant.parse("2026-01-02T12:00:00Z"), ZoneId.of("+05:30"))
        assertEquals(0x01.toByte(), india[9])
        assertEquals(0x05.toByte(), india[10])
        assertEquals(0x1E.toByte(), india[11])

        // Newfoundland = UTC-03:30 → sign 0, 3h, 30m (magnitude, not two's complement).
        val nfld = DateTimeCommand.setPayload(Instant.parse("2026-01-02T12:00:00Z"), ZoneId.of("-03:30"))
        assertEquals(0x00.toByte(), nfld[9])
        assertEquals(0x03.toByte(), nfld[10])
        assertEquals(0x1E.toByte(), nfld[11])
    }
}
