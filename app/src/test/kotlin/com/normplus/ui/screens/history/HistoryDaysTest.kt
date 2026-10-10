package com.normplus.ui.screens.history

import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** History's day totals and its paging (#100): made from records, gaps kept, years skipped in one step. */
class HistoryDaysTest {
    private val zone = ZoneId.of("Europe/London")
    private val totals = DayTotals(zone)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun sport(t: Long, steps: Int, kcal: Float = 0f) =
        SportEntity(timestampEpoch = t, steps = steps, calories = kcal, distanceMeters = 0f, avgHeartRate = 0, sportType = 0, activeMinutes = 0)

    @Test
    fun stepsAddUpByTheDayTheHalfHourEndsOn() {
        val days = totals.steps(
            listOf(
                sport(at(2026, 10, 8, 9, 29), 400),
                sport(at(2026, 10, 8, 23, 58, 30), 100), // the day's last record, stamped 23:58:30
                sport(at(2026, 10, 9, 0, 29), 0),         // a record of zero is a zero, not a gap
            ),
        )
        assertEquals(
            listOf(HistoryDay(LocalDate.of(2026, 10, 8), DayValue.Count(500)), HistoryDay(LocalDate.of(2026, 10, 9), DayValue.Count(0))),
            days,
        )
    }

    @Test
    fun caloriesAreRoundedOnceForTheDay() {
        val days = totals.calories(listOf(sport(at(2026, 10, 8, 9, 29), 0, 10.4f), sport(at(2026, 10, 8, 9, 59), 0, 10.4f)))
        assertEquals(DayValue.Count(21), days.single().value)
    }

    @Test
    fun heartRateIsTheDaysRangeAndAverage() {
        val days = totals.heartRate(
            listOf(
                HeartRateEntity(timestampEpoch = at(2026, 10, 8, 8, 0), bpm = 52),
                HeartRateEntity(timestampEpoch = at(2026, 10, 8, 14, 0), bpm = 138),
                HeartRateEntity(timestampEpoch = at(2026, 10, 8, 20, 0), bpm = 71),
            ),
        )
        assertEquals(DayValue.Pulse(low = 52, high = 138, average = 87, readings = 3), days.single().value)
    }

    @Test
    fun aNightBelongsToTheDayItEndsOnAndAddsUpByStage() {
        val session = SleepSessionEntity(id = 7, startEpoch = at(2026, 10, 8, 23, 41), endEpoch = at(2026, 10, 9, 6, 29))
        val nap = SleepSessionEntity(id = 8, startEpoch = at(2026, 10, 9, 14, 0), endEpoch = at(2026, 10, 9, 14, 30))
        val stages = listOf(
            SleepStageEntity(sessionId = 7, timestampEpoch = session.startEpoch, stage = 2, durationSeconds = 600),
            SleepStageEntity(sessionId = 7, timestampEpoch = session.startEpoch + 600_000, stage = 1, durationSeconds = 3_600),
            SleepStageEntity(sessionId = 7, timestampEpoch = session.startEpoch + 4_200_000, stage = 0, durationSeconds = 5_400),
            SleepStageEntity(sessionId = 7, timestampEpoch = session.startEpoch + 9_600_000, stage = 9, durationSeconds = 999), // unknown: left out
            SleepStageEntity(sessionId = 8, timestampEpoch = nap.startEpoch, stage = 1, durationSeconds = 1_800),
        )
        val night = totals.sleep(listOf(session, nap), stages).single()
        assertEquals(LocalDate.of(2026, 10, 9), night.date)
        val v = night.value as DayValue.Night
        assertEquals(90, v.deepMinutes)
        assertEquals(90, v.lightMinutes)
        assertEquals(10, v.awakeMinutes)
        assertEquals(180, v.asleepMinutes)
        assertEquals(2, v.sessions)
        assertEquals(session.startEpoch, v.startMillis)
        assertEquals(nap.endEpoch, v.endMillis)
    }

    @Test
    fun theChartHasOneSlotPerDayAndGapsStayEmpty() {
        val today = LocalDate.of(2026, 10, 9)
        val days = listOf(HistoryDay(today, DayValue.Count(1)), HistoryDay(today.minusDays(3), DayValue.Count(2)), HistoryDay(today.minusDays(30), DayValue.Count(3)))
        val slots = chartSlots(days, HistoryRange.Week, today)
        assertEquals(7, slots.size)
        assertEquals(listOf(null, null, null, DayValue.Count(2), null, null, DayValue.Count(1)), slots.map { it?.value })
        assertEquals(90, chartSlots(days, HistoryRange.ThreeMonths, today).size)
    }

    @Test
    fun theSummaryCountsOnlyDaysWithRecords() {
        val slots = listOf(null, HistoryDay(LocalDate.of(2026, 10, 1), DayValue.Count(9_000)), HistoryDay(LocalDate.of(2026, 10, 2), DayValue.Count(3_000)))
        val s = summarise(HistoryMetric.Steps, slots, goal = 8_000)
        assertEquals(RangeSummary(daysWithData = 2, average = 6_000, goalDays = 1), s)
        assertEquals(RangeSummary(0, null), summarise(HistoryMetric.Steps, listOf(null, null), 8_000))
        // Only steps have a goal Norm+ keeps.
        assertFalse(goalMet(HistoryMetric.Calories, slots[1]!!, 8_000))
        assertTrue(goalMet(HistoryMetric.Steps, slots[1]!!, 8_000))
    }

    /** A source over a fixed set of days, counting its queries. */
    private class FakeSource(val all: List<HistoryDay>) : HistorySource {
        var queries = 0
        override suspend fun days(metric: HistoryMetric, from: LocalDate, to: LocalDate) =
            all.filter { it.date in from..to }.sortedBy { it.date }.also { queries++ }
        override suspend fun latestDayBefore(metric: HistoryMetric, before: LocalDate) =
            all.map { it.date }.filter { it < before }.maxOrNull().also { queries++ }
        override val recordCounts: Flow<RecordCounts> = flowOf(RecordCounts(all.size, 0, 0))
    }

    @Test
    fun pagesJumpGapsAndEndWhenNothingIsOlder() = runBlocking {
        val today = LocalDate.of(2026, 10, 9)
        // Ten recent days, then nothing for two years, then three days.
        val recent = (0L until 10L).map { HistoryDay(today.minusDays(it), DayValue.Count(it.toInt())) }
        val old = (0L until 3L).map { HistoryDay(LocalDate.of(2024, 3, 3).minusDays(it), DayValue.Count(1)) }
        val source = FakeSource(recent + old)

        val first = source.page(HistoryMetric.Steps, today.plusDays(1))
        assertEquals(recent, first.days) // newest first
        val second = source.page(HistoryMetric.Steps, first.nextBefore!!)
        assertEquals(old, second.days)   // two empty years skipped in one page
        val third = source.page(HistoryMetric.Steps, second.nextBefore!!)
        assertTrue(third.days.isEmpty())
        assertNull(third.nextBefore)
        assertEquals(5, source.queries)  // two queries a page, one to find there is no more
    }
}
