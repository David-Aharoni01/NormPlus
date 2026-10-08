package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.RecordStreams
import com.norm2hacked.protocol.commands.SleepCommand
import com.norm2hacked.protocol.commands.SleepPeriod
import com.norm2hacked.protocol.commands.SleepRecord
import com.norm2hacked.protocol.commands.SleepSessions
import com.norm2hacked.protocol.commands.SleepStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sleep stream as sessions, as the official app's sync reads it (#90): a session from each
 * 0x10 to the next 0x11, each record's stage lasting until the next record, nothing outside a
 * session. The frames are the emulated watch's, recorded by its own firmware (#88).
 */
class SleepSessionsTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun records(vararg frames: String) = RecordStreams.assemble(
        frames.map { Packet(CommandCode.GET_SLEEP_DATA, Action.CHECK_RESPONSE, hex(it)) }, frames.size,
    ) { SleepCommand.parse(it) }.records

    private fun rec(seconds: Long, type: Int) = SleepRecord(seconds * 1000, type)

    // Two nights from `normwatch records --sleep 2 --asleep 90`: each the start, "awake" a minute
    // in, then the end and the last state again, at the time the clock was moved.
    private val twoShortNights = records(
        "01 00 c0 01 c4 6a 10 00 00 00", "02 00 fb 01 c4 6a 02 00 00 00",
        "03 00 1a 02 c4 6a 11 00 00 00", "04 00 1a 02 c4 6a 02 00 00 00",
        "05 00 40 53 c5 6a 10 00 00 00", "06 00 79 53 c5 6a 02 00 00 00",
        "07 00 9a 53 c5 6a 11 00 00 00", "08 00 9a 53 c5 6a 02 00 00 00",
    )

    @Test
    fun `two nights are two sessions, and the state after each end belongs to neither`() {
        val sessions = SleepSessions.group(twoShortNights)
        assertEquals(2, sessions.size)
        val first = sessions[0]
        assertEquals(0x6AC401C0L * 1000, first.startMs)
        assertEquals(0x6AC4021AL * 1000, first.endMs)
        // The start counts as awake until the first state; that state until the end.
        assertEquals(listOf(
            SleepPeriod(0x6AC401C0L * 1000, 0x6AC401FBL * 1000, SleepStage.AWAKE),
            SleepPeriod(0x6AC401FBL * 1000, 0x6AC4021AL * 1000, SleepStage.AWAKE),
        ), first.periods)
        assertEquals(90, first.seconds(SleepStage.AWAKE))
        assertEquals(0, first.asleepSeconds)
        assertEquals(0x6AC55340L * 1000, sessions[1].startMs)
        assertEquals(0x6AC5539AL * 1000, sessions[1].endMs)
    }

    @Test
    fun `deep and light are what the official app calls 0 and 1, and only they are sleep`() {
        // `--sleep 1` at its default 330 s: a still wrist goes awake, light, deep at about
        // one, two and five minutes, and the clock moved at 5:30.
        val s = SleepSessions.group(records(
            "01 00 c0 01 c4 6a 10 00 00 00", "02 00 fb 01 c4 6a 02 00 00 00",
            "03 00 37 02 c4 6a 01 00 00 00", "04 00 eb 02 c4 6a 00 00 00 00",
            "05 00 0a 03 c4 6a 11 00 00 00", "06 00 0a 03 c4 6a 02 00 00 00",
        )).single()
        assertEquals(listOf(SleepStage.AWAKE, SleepStage.AWAKE, SleepStage.LIGHT, SleepStage.DEEP),
            s.periods.map { it.stage })
        assertEquals(330, (s.endMs - s.startMs) / 1000)
        assertEquals(119, s.seconds(SleepStage.AWAKE))
        assertEquals(180, s.seconds(SleepStage.LIGHT))
        assertEquals(31, s.seconds(SleepStage.DEEP))
        assertEquals(211, s.asleepSeconds)
    }

    @Test
    fun `3 and 4 are awake too, and a type the official app does not read counts for nothing`() {
        val s = SleepSessions.group(listOf(
            rec(0, 0x10), rec(10, 3), rec(20, 4), rec(30, 7), rec(40, 1), rec(50, 0x11),
        )).single()
        assertEquals(listOf(
            SleepPeriod(0, 10_000, SleepStage.AWAKE),
            SleepPeriod(10_000, 20_000, SleepStage.AWAKE),
            SleepPeriod(20_000, 30_000, SleepStage.AWAKE),
            SleepPeriod(40_000, 50_000, SleepStage.LIGHT),
        ), s.periods)
    }

    @Test
    fun `a session with no end is dropped, as the official app drops it`() {
        // A start that a second start follows, and the stream's last session left open.
        val sessions = SleepSessions.group(listOf(
            rec(0, 0x10), rec(60, 2),
            rec(100, 0x10), rec(160, 1), rec(200, 0x11),
            rec(300, 0x10), rec(360, 2),
        ))
        assertEquals(listOf(100_000L to 200_000L), sessions.map { it.startMs to it.endMs })
    }

    @Test
    fun `records outside a session are not part of one`() {
        val sessions = SleepSessions.group(listOf(
            rec(0, 2), rec(5, 0x11), rec(10, 0x10), rec(20, 1), rec(30, 0x11), rec(40, 0),
        ))
        assertEquals(1, sessions.size)
        assertEquals(10_000L to 30_000L, sessions[0].startMs to sessions[0].endMs)
    }

    @Test
    fun `out-of-order records are sorted, and an end keeps its place before the state that shares its time`() {
        val shuffled = listOf(rec(1400, 0x11), rec(1400, 0), rec(1060, 2), rec(1000, 0x10), rec(1120, 1))
        val s = SleepSessions.group(shuffled).single()
        assertEquals(1000_000L to 1400_000L, s.startMs to s.endMs)
        assertEquals(listOf(SleepStage.AWAKE, SleepStage.AWAKE, SleepStage.LIGHT), s.periods.map { it.stage })
    }

    @Test
    fun `0x12 ends a session, because the parse reads it as 0x11`() {
        val ended = records("01 00 00 00 00 6a 10 00 00 00", "02 00 3c 00 00 6a 12 00 00 00")
        assertTrue(SleepSessions.group(ended).single().endMs > 0)
    }
}
