package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.HeartRateCommand
import com.norm2hacked.protocol.commands.RecordStreams
import com.norm2hacked.protocol.commands.SportCommand
import com.norm2hacked.protocol.commands.SyncCountCommand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sync's reads (#85): one count, then each record type as one stream of frames.
 *
 * The frames here are real: the physical watch's, read on 2026-10-07 (920 sport records, 271
 * heart-rate), and the emulated watch's, written by its own firmware (#51,
 * tools/tests/test_health_records.py).
 */
class RecordStreamTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun sport(payload: String) = Packet(CommandCode.GET_SPORT_DATA, Action.CHECK_RESPONSE, hex(payload))
    private fun hr(payload: String) = Packet(CommandCode.GET_HEART_RATE_DATA, Action.CHECK_RESPONSE, hex(payload))

    // The physical watch's first sport record: a :59 tick, nothing walked.
    private val physicalSport = "01 00 14 c5 ac 6a 00 00 00 00 e8 80 00 00 00 00 00 00 00 00 00 00 00 09 e8 80 00 00"
    // The emulated watch's, after ~70 s of walking: 261 steps, 186 m (devices.Walking).
    private val emulatedWalk = "01 00 bc f4 c5 6a 05 01 00 00 40 1f 00 00 ba 00 00 00 01 00 00 00 00 08 e8 03 00 00"

    @Test
    fun `every count comes from the one TOTAL_SPORT_SLEEP_COUNT reply`() {
        val reply = Packet(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK_RESPONSE, hex("98 03 00 00 0f 01 00 00"))
        val counts = SyncCountCommand.parseCounts(reply)
        assertEquals(920, counts.sport)
        assertEquals(0, counts.sleep)
        assertEquals(271, counts.heartRate)
    }

    @Test
    fun `a reply too short for the heart-rate count has none, as AllDataTypeCount reads it`() {
        val reply = Packet(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK_RESPONSE, hex("05 00 02 00"))
        val counts = SyncCountCommand.parseCounts(reply)
        assertEquals(5, counts.sport)
        assertEquals(2, counts.sleep)
        assertEquals(0, counts.heartRate)
    }

    @Test
    fun `the requests that start a stream are the official app's`() {
        // GetSportData(cb, 2, 0, count) and GetHeartRateData(cb, 1, 0, count).
        assertContentEquals(hex("6F 54 70 02 00 00 00 8F"),
            PacketBuilder.build(CommandCode.GET_SPORT_DATA, Action.CHECK, RecordStreams.SPORT_REQUEST))
        assertContentEquals(hex("6F 5B 70 01 00 00 8F"),
            PacketBuilder.build(CommandCode.GET_HEART_RATE_DATA, Action.CHECK, RecordStreams.SINGLE_BYTE_REQUEST))
    }

    @Test
    fun `both watches' sport records parse`() {
        val physical = SportCommand.parse(sport(physicalSport))!!
        assertEquals(0x6AACC514L * 1000, physical.timestampMs)
        assertEquals(0, physical.steps)
        assertEquals(33.0f, physical.calories)
        assertEquals(9, physical.sportType)

        val walked = SportCommand.parse(sport(emulatedWalk))!!
        assertEquals(0x6AC5F4BCL * 1000, walked.timestampMs)
        assertEquals(261, walked.steps)
        assertEquals(8.0f, walked.calories)
        assertEquals(186.0f, walked.distanceMeters)
    }

    @Test
    fun `the physical watch's heart-rate record parses`() {
        val rec = HeartRateCommand.parse(hr("01 00 36 7f 2e 6a 4a"))!!
        assertEquals(0x6A2E7F36L * 1000, rec.timestampMs)
        assertEquals(0x4A, rec.bpm)
    }

    @Test
    fun `the stream ends at the frame indexed count`() {
        val last = RecordStreams.isLast(3)
        assertFalse(last(sport("02 00" + physicalSport.substring(6))))
        assertTrue(last(sport("03 00" + physicalSport.substring(6))))
    }

    private fun sportAt(index: Int) =
        sport("%02x %02x".format(index and 0xFF, index shr 8) + physicalSport.substring(5))

    @Test
    fun `a whole stream assembles in index order`() {
        val stream = RecordStreams.assemble(listOf(sportAt(1), sportAt(2), sportAt(3)), 3) { SportCommand.parse(it) }
        assertTrue(stream.complete)
        assertEquals(3, stream.records.size)
        assertEquals(0, stream.duplicates)
    }

    @Test
    fun `what the old per-index loop got back is three records, the rest duplicates`() {
        // Asking for each index restarted the stream every time: 1, 2, 1, 2, 3, 2, ...
        val got = listOf(1, 2, 1, 2, 3, 2, 2, 3, 2, 3).map { sportAt(it) }
        val stream = RecordStreams.assemble(got, 10) { SportCommand.parse(it) }
        assertEquals(3, stream.records.size)
        assertEquals(7, stream.duplicates)
        assertEquals((4..10).toList(), stream.missing)
        assertFalse(stream.complete)
    }

    @Test
    fun `a stream that stopped short says what never came`() {
        val stream = RecordStreams.assemble(listOf(sportAt(1), sportAt(2), sportAt(4)), 5) { SportCommand.parse(it) }
        assertEquals(listOf(3, 5), stream.missing)
        assertEquals(3, stream.records.size)
        assertFalse(stream.complete)
    }

    @Test
    fun `a frame that does not parse or is out of range is counted, not kept`() {
        val short = sport("02 00 44")                    // too short to hold a timestamp
        val stream = RecordStreams.assemble(listOf(sportAt(1), short, sportAt(7)), 2) { SportCommand.parse(it) }
        assertEquals(1, stream.records.size)
        assertEquals(2, stream.unparsed)
        assertTrue(stream.missing.isEmpty())
        assertFalse(stream.complete)
    }
}
