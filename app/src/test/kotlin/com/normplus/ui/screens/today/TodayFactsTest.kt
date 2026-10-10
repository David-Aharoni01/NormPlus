package com.normplus.ui.screens.today

import com.normplus.domain.usecase.SyncStage
import com.normplus.status.SyncProblem
import com.normplus.status.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TodayFactsTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val day = LocalDate.of(2026, 10, 9)
    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun le(vararg ints: Int): ByteArray = ints.flatMap { v -> (0..3).map { ((v shr (8 * it)) and 0xFF).toByte() } }.toByteArray()

    @Test
    fun `a full 0x57 reply gives every field`() {
        val s = TodaySummary.fromPayload(le(6412, 1840, 4630, 408, 52, 72, 3), 1000L)!!
        assertEquals(TodaySummary(1000L, 6412, 1840, 4630, 408, 52, 72), s)
    }

    @Test
    fun `fields the reply did not carry are absent, not zero`() {
        val s = TodaySummary.fromPayload(le(6412, 1840), 1000L)!!
        assertEquals(6412, s.steps)
        assertEquals(1840, s.calories)
        assertNull(s.distanceMeters)
        assertNull(s.activeMinutes)
        assertNull(s.heartRate)
    }

    @Test
    fun `a heart rate of zero is no reading, a step count of zero is a count`() {
        val s = TodaySummary.fromPayload(le(0, 0, 0, 0, 0, 0), 1000L)!!
        assertEquals(0, s.steps)
        assertNull(s.heartRate)
    }

    @Test
    fun `a reply with no whole field is no reading`() {
        assertNull(TodaySummary.fromPayload(byteArrayOf(0x57, 0x00), 1000L))
        assertNull(TodaySummary.fromPayload(ByteArray(0), 1000L))
    }

    @Test
    fun `encode and decode round trip, absent fields included`() {
        val s = TodaySummary(123456789L, 6412, null, 4630, 0, null, 72)
        assertEquals(s, TodaySummary.decode(s.encode()))
        assertNull(TodaySummary.decode("garbage"))
        assertNull(TodaySummary.decode(null))
    }

    @Test
    fun `yesterday's summary says nothing about today`() {
        val s = TodaySummary(at(day.minusDays(1), 23, 50), 9000, null, null, null, null, null)
        assertNull(TodayFacts.summaryFor(day, s, zone))
        assertEquals(s, TodayFacts.summaryFor(day.minusDays(1), s, zone))
    }

    @Test
    fun `as of while away or when the reading is old, live otherwise`() {
        val read = at(day, 9, 12)
        val s = TodaySummary(read, 1, null, null, null, null, null)
        assertNull(TodayFacts.asOf(s, connected = true, nowEpochMs = read + 60_000))
        assertEquals(read, TodayFacts.asOf(s, connected = false, nowEpochMs = read + 60_000))
        assertEquals(read, TodayFacts.asOf(s, connected = true, nowEpochMs = read + TodayFacts.LIVE_FOR_MS))
        assertNull(TodayFacts.asOf(null, connected = false, nowEpochMs = read))
    }

    @Test
    fun `last night runs from noon to noon`() {
        val w = TodayFacts.lastNightWindow(day, zone)
        assertEquals(at(day.minusDays(1), 12, 0), w.first)
        assertEquals(at(day, 12, 0) - 1, w.last)
    }

    @Test
    fun `a shortfall comes from a finished sync with problems, until dismissed`() {
        val problem = SyncProblem(SyncStage.Sport, 900, 922, "timeout")
        assertEquals(SyncShortfall(5L, 900, 922), TodayFacts.shortfall(SyncState.Finished(5L, listOf(problem)), null))
        assertNull(TodayFacts.shortfall(SyncState.Finished(5L, listOf(problem)), 5L))
        assertNull(TodayFacts.shortfall(SyncState.Finished(5L, emptyList()), null))
        assertNull(TodayFacts.shortfall(SyncState.Idle, null))
        assertEquals(
            SyncShortfall(6L, null, null),
            TodayFacts.shortfall(SyncState.Finished(6L, listOf(SyncProblem(null, null, null, "lost"))), null),
        )
    }

    @Test
    fun `synced sleep and heart rate win over the watch's summary`() {
        val summary = TodaySummary(1L, null, null, null, 395, null, 68)
        val synced = SleepSummary(408, 92, 1L, 2L)
        assertEquals(synced, TodayFacts.sleep(synced, summary))
        assertEquals(SleepSummary(395, null, null, null), TodayFacts.sleep(null, summary))
        assertNull(TodayFacts.sleep(null, summary.copy(sleepMinutes = 0)))
        assertEquals(HeartReading(68, null), TodayFacts.heartRate(null, summary))
        assertEquals(HeartReading(72, 3L), TodayFacts.heartRate(HeartReading(72, 3L), summary))
    }
}
