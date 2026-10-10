package com.normplus.ui.screens.daydetail

import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.ui.components.SleepStage
import com.normplus.ui.screens.history.DayTotals
import com.normplus.ui.screens.history.DayValue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayDetailDataTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val builder = DayBuilder(zone)
    private val day = LocalDate.of(2026, 10, 8)

    private fun at(date: LocalDate, h: Int, m: Int, s: Int = 0) =
        LocalDateTime.of(date.year, date.month, date.dayOfMonth, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun sport(t: Long, steps: Int, kcal: Float = 10f, metres: Float = 100f, active: Int = 5) =
        SportEntity(timestampEpoch = t, steps = steps, calories = kcal, distanceMeters = metres, avgHeartRate = 0, sportType = 0, activeMinutes = active)

    /** The watch's own stamps (:29, :59, the day's last at 23:58:30) land in their half hours. */
    @Test
    fun halfHoursByTheWatchsStamps() {
        val records = listOf(
            sport(at(day, 0, 29), 10),
            sport(at(day, 8, 59), 900),
            sport(at(day, 23, 58, 30), 40),
            // The next day's first record and the previous day's last stay out.
            sport(at(day.plusDays(1), 0, 29), 77),
            sport(at(day.minusDays(1), 23, 58, 30), 66),
        )
        val d = builder.build(day, records, emptyList(), emptyList(), emptyList())
        assertEquals(48, d.halfHours.size)
        assertEquals(10, d.halfHours[0])
        assertEquals(900, d.halfHours[17])
        assertEquals(40, d.halfHours[47])
        assertEquals(45, d.halfHours.count { it == null })
        assertEquals(950, d.steps)
        assertEquals(17, d.busiestHalfHour)
    }

    /** A count's part of a half hour and the tick's rest add up in that half hour, as in the day's total. */
    @Test
    fun aPartAndItsRestShareAHalfHour() {
        val records = listOf(sport(at(day, 14, 12, 40), 300), sport(at(day, 14, 29), 200))
        val d = builder.build(day, records, emptyList(), emptyList(), emptyList())
        assertEquals(500, d.halfHours[28])
        assertEquals(500, d.steps)
    }

    /** The totals are exactly History's (#100), and a zero is a zero while no record is absent. */
    @Test
    fun totalsAreHistorysAndZeroIsNotAbsent() {
        val records = listOf(sport(at(day, 9, 29), 0, kcal = 1.4f, metres = 10.4f, active = 0), sport(at(day, 9, 59), 120, kcal = 2.3f, metres = 80.3f, active = 3))
        val d = builder.build(day, records, emptyList(), emptyList(), emptyList())
        val history = DayTotals(zone)
        assertEquals((history.steps(records).single().value as DayValue.Count).value, d.steps)
        assertEquals((history.calories(records).single().value as DayValue.Count).value, d.calories)
        assertEquals(4, d.calories)
        assertEquals(91, d.distanceMeters)
        assertEquals(3, d.activeMinutes)
        assertEquals(0, d.halfHours[18])

        val none = builder.build(day, emptyList(), emptyList(), emptyList(), emptyList())
        assertNull(none.steps)
        assertNull(none.calories)
        assertNull(none.distanceMeters)
        assertNull(none.activeMinutes)
        assertNull(none.busiestHalfHour)
        assertTrue(none.isEmpty)
    }

    /** A night counts on the day it ends: last night's session is this day's, tonight's is tomorrow's. */
    @Test
    fun aNightCountsOnTheDayItEnds() {
        val last = SleepSessionEntity(id = 1, startEpoch = at(day.minusDays(1), 23, 40), endEpoch = at(day, 6, 30))
        val tonight = SleepSessionEntity(id = 2, startEpoch = at(day, 23, 10), endEpoch = at(day.plusDays(1), 7, 0))
        val stages = listOf(
            SleepStageEntity(sessionId = 1, timestampEpoch = last.startEpoch, stage = 2, durationSeconds = 10 * 60),
            SleepStageEntity(sessionId = 1, timestampEpoch = last.startEpoch + 10 * 60_000, stage = 1, durationSeconds = 200 * 60),
            SleepStageEntity(sessionId = 1, timestampEpoch = last.startEpoch + 210 * 60_000, stage = 0, durationSeconds = 90 * 60),
            SleepStageEntity(sessionId = 1, timestampEpoch = last.startEpoch + 300 * 60_000, stage = 9, durationSeconds = 50 * 60),
            SleepStageEntity(sessionId = 2, timestampEpoch = tonight.startEpoch, stage = 0, durationSeconds = 60 * 60),
        )
        val d = builder.build(day, emptyList(), emptyList(), listOf(last, tonight), stages)
        assertEquals(1, d.sleep.size)
        val s = d.sleep.single()
        assertEquals(90, s.deepMinutes)
        assertEquals(200, s.lightMinutes)
        assertEquals(10, s.awakeMinutes)
        assertEquals(290, s.asleepMinutes)
        // An unknown stage code is left out, not guessed at.
        assertEquals(listOf(SleepStage.Awake, SleepStage.Light, SleepStage.Deep), s.spans.map { it.stage })
        assertEquals(last.startEpoch + 10 * 60_000, s.spans[0].endMillis)
        // The night's totals are History's.
        val night = d.night!!
        assertEquals(290, night.asleepMinutes)
        assertEquals(last.startEpoch, night.startMillis)
        assertEquals(last.endEpoch, night.endMillis)
    }

    /** Readings in time order; two in five minutes show as their range, one as itself. */
    @Test
    fun heartReadings() {
        val readings = listOf(
            HeartRateEntity(timestampEpoch = at(day, 14, 3), bpm = 80),
            HeartRateEntity(timestampEpoch = at(day, 6, 0), bpm = 58),
            HeartRateEntity(timestampEpoch = at(day, 14, 1), bpm = 92),
            HeartRateEntity(timestampEpoch = at(day.plusDays(1), 0, 1), bpm = 140),
        )
        val d = builder.build(day, emptyList(), readings, emptyList(), emptyList())
        assertEquals(listOf(58, 92, 80), d.heart.map { it.bpm })
        assertEquals(58, d.heartLow)
        assertEquals(92, d.heartHigh)
        assertEquals(77, d.heartAverage)
        val slots = builder.heartSlots(d.heart)
        assertEquals(288, slots.size)
        assertEquals(58..58, slots[72])
        assertEquals(80..92, slots[168])
        assertEquals(2, slots.count { it != null })
        assertTrue(!d.isEmpty)
    }

    @Test
    fun movingBetweenDays() {
        val today = LocalDate.of(2026, 10, 9)
        assertTrue(canGoNext(day, today))
        assertTrue(!canGoNext(today, today))
        // Left to right, the earlier day lies to the left: dragging right brings it in.
        assertEquals(-1, swipeDirection(200f, 100f, rtl = false))
        assertEquals(1, swipeDirection(-200f, 100f, rtl = false))
        assertEquals(1, swipeDirection(200f, 100f, rtl = true))
        assertEquals(-1, swipeDirection(-200f, 100f, rtl = true))
        assertEquals(0, swipeDirection(60f, 100f, rtl = false))
    }
}
