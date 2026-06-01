package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.DateTimeCommand
import java.time.Instant
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
}
