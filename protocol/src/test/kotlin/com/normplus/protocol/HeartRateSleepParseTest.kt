package com.normplus.protocol

import com.normplus.protocol.commands.HeartRateCommand
import com.normplus.protocol.commands.SleepCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the HR/sleep record off-by-one: both record types carry a 2-byte index
 * (GetHeartRateData/GetSleepData.smali: index = bytesToLong(0,1)), so the timestamp is at offset 2
 * and the value byte at offset 6 — not offset 1 / byte 5 as the parsers previously assumed.
 */
class HeartRateSleepParseTest {

    // 7-byte record: [index(2 LE)] [timestamp(4 LE, offset 2)] [value(1, offset 6)]
    private fun record(index: Int, timestampSec: Long, value: Int): ByteArray {
        val b = ByteArray(7)
        b[0] = (index and 0xFF).toByte()
        b[1] = ((index shr 8) and 0xFF).toByte()
        val ts = timestampSec.toInt()
        b[2] = (ts and 0xFF).toByte()
        b[3] = ((ts shr 8) and 0xFF).toByte()
        b[4] = ((ts shr 16) and 0xFF).toByte()
        b[5] = ((ts shr 24) and 0xFF).toByte()
        b[6] = value.toByte()
        return b
    }

    private fun hrPacket(p: ByteArray) = Packet(CommandCode.GET_HEART_RATE_DATA, Action.CHECK_RESPONSE, p)
    private fun sleepPacket(p: ByteArray) = Packet(CommandCode.GET_SLEEP_DATA, Action.CHECK_RESPONSE, p)

    @Test
    fun `heart rate reads timestamp at offset 2 and bpm at byte 6`() {
        val rec = HeartRateCommand.parse(hrPacket(record(index = 1, timestampSec = 1_779_036_228L, value = 72)))!!
        assertEquals(1_779_036_228L * 1000L, rec.timestampMs, "timestamp must read bytes [2..5]")
        assertEquals(72, rec.bpm, "bpm must read byte [6]")
    }

    @Test
    fun `sleep reads timestamp at offset 2 and stage at byte 6, normalising 0x12 to 0x11`() {
        val rec = SleepCommand.parse(sleepPacket(record(index = 3, timestampSec = 1_779_000_000L, value = 0x11)))!!
        assertEquals(1_779_000_000L * 1000L, rec.timestampMs, "timestamp must read bytes [2..5]")
        assertEquals(0x11, rec.stage, "stage must read byte [6]")

        // The watch's 0x12 deep-sleep variant is normalised to 0x11 (GetSleepData.smali).
        val mapped = SleepCommand.parse(sleepPacket(record(index = 4, timestampSec = 1_779_000_300L, value = 0x12)))!!
        assertEquals(0x11, mapped.stage)
    }

    @Test
    fun `both parsers reject a record too short for index + timestamp + value`() {
        val sixBytes = byteArrayOf(0x01, 0x00, 0x44, 0xEA.toByte(), 0x1B, 0x6A)
        assertNull(HeartRateCommand.parse(hrPacket(sixBytes)))
        assertNull(SleepCommand.parse(sleepPacket(sixBytes)))
    }
}
