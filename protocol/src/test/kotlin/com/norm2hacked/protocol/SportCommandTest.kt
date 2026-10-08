package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.SportCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportCommandTest {

    // Builds a 28-byte sport record payload to the exact layout decoded from
    // GetSportData.smali / SportBT.<init>(IIIIIIJII) — all fields little-endian.
    private fun record(
        index: Int = 1,
        timestampSec: Long = 1_779_036_228L, // ≈ 2026-05-28, a realistic "now"
        steps: Int = 1234,
        caloriesRaw: Int = 33_000,
        distance: Int = 500,
        activeMinutes: Int = 11,
        avgBpm: Int = 72,
        type: Int = 1,
        staticCalorie: Int = 5000,
    ): ByteArray {
        val b = ByteArray(28)
        fun put16(off: Int, v: Int) { b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte() }
        fun put32(off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        put16(0, index)
        put32(2, timestampSec.toInt())
        put32(6, steps)
        put32(10, caloriesRaw)
        put32(14, distance)
        put32(18, activeMinutes)
        b[22] = avgBpm.toByte()
        b[23] = type.toByte()
        put32(24, staticCalorie)
        return b
    }

    private fun packet(payload: ByteArray) =
        Packet(CommandCode.GET_SPORT_DATA, Action.CHECK_RESPONSE, payload)

    @Test
    fun `parses every field at the correct offset`() {
        val rec = SportCommand.parse(packet(record()))!!
        assertEquals(1_779_036_228L * 1000L, rec.timestampMs, "timestamp [2..5] in ms")
        assertEquals(1234, rec.steps, "steps [6..9]")
        assertEquals(33.0f, rec.calories, "calories [10..13] / 1000")
        assertEquals(500.0f, rec.distanceMeters, "distance [14..17]")
        assertEquals(11, rec.activeMinutes, "sportTime [18..21], in minutes")
        assertEquals(72, rec.avgHr, "avgBpm single byte [22]")
        assertEquals(1, rec.sportType, "type single byte [23]")
    }

    @Test
    fun `timestamp lands in a plausible recent range (regression for the +1 offset bug)`() {
        // The old parser read the timestamp one byte early, yielding values decades off (≈1984),
        // so records never matched "today" and the dashboard showed 0. Guard against regressions.
        val rec = SportCommand.parse(packet(record(timestampSec = 1_779_036_228L)))!!
        val year2020Ms = 1_577_836_800_000L
        val year2100Ms = 4_102_444_800_000L
        assert(rec.timestampMs in year2020Ms..year2100Ms) {
            "timestamp ${rec.timestampMs} should be in a realistic modern range"
        }
    }

    @Test
    fun `returns null for a payload too short to hold a timestamp`() {
        assertNull(SportCommand.parse(packet(byteArrayOf(0x01, 0x00, 0x44))))
    }
}
