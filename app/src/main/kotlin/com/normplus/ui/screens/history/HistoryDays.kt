package com.normplus.ui.screens.history

import androidx.compose.runtime.Immutable
import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.ui.components.SleepStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * History's day totals (#100), made from the synced records (#95 §7.1: Today shows the
 * watch's own total, History adds up the records). Pure: no Android, no database, so the
 * unit tests pin them.
 *
 * A day with no record of a metric has no value: it is a gap in the chart and has no row.
 * A day whose records say zero is a zero, a stub on the baseline.
 */

/** History's metric tabs, in their order. */
enum class HistoryMetric { Steps, HeartRate, Sleep, Calories }

/** History's chart ranges: the last [days] days, today included. */
enum class HistoryRange(val days: Int) {
    Week(7),
    Month(30),
    ThreeMonths(90),
    ;

    /** The range's first day, when it ends on [today]. */
    fun firstDay(today: LocalDate): LocalDate = today.minusDays(days - 1L)
}

/** One day's figures for one metric. */
@Immutable
sealed interface DayValue {
    /** Steps, or kilocalories rounded to the nearest one: the day's half-hour records added up. */
    data class Count(val value: Int) : DayValue

    /** The day's heart-rate readings: lowest, highest, their average, how many. */
    data class Pulse(val low: Int, val high: Int, val average: Int, val readings: Int) : DayValue

    /**
     * The night that ended on this day, by stage, in minutes; several sessions (a nap, a broken
     * night) are added together. Asleep is deep plus light, as the official app counts it
     * (`SleepBreakdown.totalSec`). [startMillis] / [endMillis]: the first session's start and
     * the last one's end.
     */
    data class Night(
        val deepMinutes: Int,
        val lightMinutes: Int,
        val awakeMinutes: Int,
        val startMillis: Long,
        val endMillis: Long,
        val sessions: Int,
    ) : DayValue {
        val asleepMinutes: Int get() = deepMinutes + lightMinutes
    }
}

/** A day and its figures for the metric on show. */
@Immutable
data class HistoryDay(val date: LocalDate, val value: DayValue)

/** Turns records into days, in [zone] (the phone's). */
class DayTotals(private val zone: ZoneId = ZoneId.systemDefault()) {

    fun dayOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** The first instant of [date]. */
    fun startOf(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Steps per day: every half-hour record stamped on that day (the watch stamps a half hour at its end). */
    fun steps(records: List<SportEntity>): List<HistoryDay> =
        records.groupBy { dayOf(it.timestampEpoch) }
            .map { (day, rs) -> HistoryDay(day, DayValue.Count(rs.sumOf { it.steps })) }
            .sortedBy { it.date }

    /** Kilocalories per day, added up as the records hold them and rounded once. */
    fun calories(records: List<SportEntity>): List<HistoryDay> =
        records.groupBy { dayOf(it.timestampEpoch) }
            .map { (day, rs) -> HistoryDay(day, DayValue.Count(rs.sumOf { it.calories.toDouble() }.roundToInt())) }
            .sortedBy { it.date }

    /** Heart rate per day: the range of the day's readings and their average. */
    fun heartRate(samples: List<HeartRateEntity>): List<HistoryDay> =
        samples.groupBy { dayOf(it.timestampEpoch) }
            .map { (day, ss) ->
                val bpm = ss.map { it.bpm }
                HistoryDay(day, DayValue.Pulse(bpm.min(), bpm.max(), bpm.average().roundToInt(), bpm.size))
            }
            .sortedBy { it.date }

    /**
     * Sleep per day: the sessions that ended on it, each stage's time added up from its stages
     * (each lasting until the next record, #90). A stage code outside deep, light and awake
     * is left out rather than guessed at.
     */
    fun sleep(sessions: List<SleepSessionEntity>, stages: List<SleepStageEntity>): List<HistoryDay> {
        val bySession = stages.groupBy { it.sessionId }
        return sessions.groupBy { dayOf(it.endEpoch) }
            .map { (day, ss) ->
                var deep = 0
                var light = 0
                var awake = 0
                ss.forEach { session ->
                    bySession[session.id].orEmpty().forEach { st ->
                        when (SleepStage.fromCode(st.stage)) {
                            SleepStage.Deep -> deep += st.durationSeconds
                            SleepStage.Light -> light += st.durationSeconds
                            SleepStage.Awake -> awake += st.durationSeconds
                            null -> Unit
                        }
                    }
                }
                HistoryDay(
                    day,
                    DayValue.Night(
                        deepMinutes = deep / 60,
                        lightMinutes = light / 60,
                        awakeMinutes = awake / 60,
                        startMillis = ss.minOf { it.startEpoch },
                        endMillis = ss.maxOf { it.endEpoch },
                        sessions = ss.size,
                    ),
                )
            }
            .sortedBy { it.date }
    }
}

/**
 * The chart's slots: one per day of [range] ending on [today], oldest first, the day's value
 * or null where no record came.
 */
fun chartSlots(days: List<HistoryDay>, range: HistoryRange, today: LocalDate): List<HistoryDay?> {
    val byDate = days.associateBy { it.date }
    val first = range.firstDay(today)
    return (0 until range.days).map { byDate[first.plusDays(it.toLong())] }
}

/** What the chart's card says about its range, from the days that have a value. */
@Immutable
data class RangeSummary(
    /** Days in the range with a value. */
    val daysWithData: Int,
    /** Steps or kcal: the average day; heart rate: the average of the days' averages; sleep: the average night asleep, minutes. */
    val average: Int?,
    /** Heart rate only: the range's lowest and highest reading. */
    val low: Int? = null,
    val high: Int? = null,
    /** Steps only: days at or over the goal. */
    val goalDays: Int = 0,
)

fun summarise(metric: HistoryMetric, slots: List<HistoryDay?>, goal: Int?): RangeSummary {
    val values = slots.filterNotNull().map { it.value }
    if (values.isEmpty()) return RangeSummary(0, null)
    return when (metric) {
        HistoryMetric.Steps, HistoryMetric.Calories -> {
            val counts = values.filterIsInstance<DayValue.Count>().map { it.value }
            RangeSummary(
                daysWithData = counts.size,
                average = counts.average().roundToInt(),
                goalDays = if (goal != null && goal > 0) counts.count { it >= goal } else 0,
            )
        }
        HistoryMetric.HeartRate -> {
            val pulses = values.filterIsInstance<DayValue.Pulse>()
            RangeSummary(
                daysWithData = pulses.size,
                average = pulses.map { it.average }.average().roundToInt(),
                low = pulses.minOf { it.low },
                high = pulses.maxOf { it.high },
            )
        }
        HistoryMetric.Sleep -> {
            val nights = values.filterIsInstance<DayValue.Night>()
            RangeSummary(nights.size, nights.map { it.asleepMinutes }.average().roundToInt())
        }
    }
}

/** Whether [day] met the step [goal]. Only steps have a goal Norm+ keeps. */
fun goalMet(metric: HistoryMetric, day: HistoryDay, goal: Int?): Boolean =
    metric == HistoryMetric.Steps && goal != null && goal > 0 &&
        (day.value as? DayValue.Count)?.value?.let { it >= goal } == true
