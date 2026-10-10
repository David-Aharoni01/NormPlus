package com.normplus.ui.screens.daydetail

import androidx.compose.runtime.Immutable
import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.ui.components.SleepStage
import com.normplus.ui.components.StageSpan
import com.normplus.ui.screens.history.DayTotals
import com.normplus.ui.screens.history.DayValue
import com.normplus.ui.screens.history.HistoryDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * One day's records (#101), made from what the sync stored, by History's rules (#100):
 * the day's totals are its records added up (DayTotals), and a night counts on the day it
 * ends. Pure: no Android, no database, so the unit tests pin it.
 *
 * Nothing is smoothed or filled in. A half hour with no record is a gap, a record of zero
 * is a zero, and a figure with no record behind it is null: absent, never zero.
 */

/** Half hours in a day: the steps chart's slots. */
const val HALF_HOURS = 48

/** The heart-rate chart's slot: five minutes, so each reading stands at its own time. */
const val HEART_SLOT_MINUTES = 5

/** Five-minute slots in a day. */
const val HEART_SLOTS = 24 * 60 / HEART_SLOT_MINUTES

/** One heart-rate reading, as the watch measured it. */
@Immutable
data class HeartPoint(val atMillis: Long, val bpm: Int)

/** One sleep session ending on the day: its stages as spans, and each stage's time in minutes. */
@Immutable
data class SleepSessionDetail(
    val startMillis: Long,
    val endMillis: Long,
    val spans: List<StageSpan>,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
) {
    /** Asleep is deep plus light, as the official app counts it (`SleepBreakdown.totalSec`). */
    val asleepMinutes: Int get() = deepMinutes + lightMinutes
}

/** Everything Day detail shows about one day. */
@Immutable
data class DayRecords(
    val date: LocalDate,
    /** The day's steps, kilocalories, metres and active minutes, its sport records added up; null with no record. */
    val steps: Int?,
    val calories: Int?,
    val distanceMeters: Int?,
    val activeMinutes: Int?,
    /** [HALF_HOURS] slots from midnight: the steps recorded for each half hour, null where no record came. */
    val halfHours: List<Int?>,
    /** The day's heart-rate readings, in time order. */
    val heart: List<HeartPoint>,
    /** The sessions that ended on this day (the night before it, and any nap), in time order. */
    val sleep: List<SleepSessionDetail>,
    /** The night's totals, exactly as History adds them up; null with no session. */
    val night: DayValue.Night?,
) {
    val hasSport: Boolean get() = steps != null
    val isEmpty: Boolean get() = steps == null && heart.isEmpty() && sleep.isEmpty()

    val heartLow: Int? get() = heart.minOfOrNull { it.bpm }
    val heartHigh: Int? get() = heart.maxOfOrNull { it.bpm }
    val heartAverage: Int? get() = if (heart.isEmpty()) null else heart.map { it.bpm }.average().roundToInt()

    /** The half hour with the most steps (the first of equals), or null when no half hour has any. */
    val busiestHalfHour: Int?
        get() = halfHours.withIndex().filter { (it.value ?: 0) > 0 }.maxByOrNull { it.value ?: 0 }?.index
}

/** Turns one day's records into [DayRecords], in [zone] (the phone's). */
class DayBuilder(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val totals = DayTotals(zone)

    /** The half hour [epochMillis] falls in, counted from midnight. */
    fun halfHourOf(epochMillis: Long): Int = minuteOfDay(epochMillis) / 30

    /** The five-minute slot [epochMillis] falls in, counted from midnight. */
    fun heartSlotOf(epochMillis: Long): Int = minuteOfDay(epochMillis) / HEART_SLOT_MINUTES

    private fun minuteOfDay(epochMillis: Long): Int {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    /**
     * [date]'s figures from the records given (anything stamped on another day is left out).
     *
     * The watch writes a half hour's sport record at its :29 or :59 tick, stamped with that
     * minute (the day's last one 23:58:30), and when a phone counts the records it writes the
     * part of the current half hour so far, stamped with the time asked (tools/normplus/watch/
     * fw/health.py, #51). So a record's half hour is the one its stamp falls in, and a half
     * hour holding both a part and the rest shows them added together: the half hour's steps.
     */
    fun build(
        date: LocalDate,
        sport: List<SportEntity>,
        heartRate: List<HeartRateEntity>,
        sessions: List<SleepSessionEntity>,
        stages: List<SleepStageEntity>,
    ): DayRecords {
        val daySport = sport.filter { totals.dayOf(it.timestampEpoch) == date }
        val dayHeart = heartRate.filter { totals.dayOf(it.timestampEpoch) == date }.sortedBy { it.timestampEpoch }
        val daySessions = sessions.filter { totals.dayOf(it.endEpoch) == date }.sortedBy { it.startEpoch }
        val sessionIds = daySessions.map { it.id }.toSet()
        val dayStages = stages.filter { it.sessionId in sessionIds }

        val halfHours = arrayOfNulls<Int>(HALF_HOURS)
        daySport.forEach { r ->
            val slot = halfHourOf(r.timestampEpoch).coerceIn(0, HALF_HOURS - 1)
            halfHours[slot] = (halfHours[slot] ?: 0) + r.steps
        }

        val bySession = dayStages.groupBy { it.sessionId }
        val sleep = daySessions.map { s ->
            val spans = bySession[s.id].orEmpty().sortedBy { it.timestampEpoch }.mapNotNull { st ->
                SleepStage.fromCode(st.stage)?.let { StageSpan(st.timestampEpoch, st.timestampEpoch + st.durationSeconds * 1000L, it) }
            }
            fun seconds(stage: SleepStage) = bySession[s.id].orEmpty()
                .filter { SleepStage.fromCode(it.stage) == stage }.sumOf { it.durationSeconds }
            SleepSessionDetail(
                startMillis = s.startEpoch,
                endMillis = s.endEpoch,
                spans = spans,
                deepMinutes = seconds(SleepStage.Deep) / 60,
                lightMinutes = seconds(SleepStage.Light) / 60,
                awakeMinutes = seconds(SleepStage.Awake) / 60,
            )
        }

        fun count(days: List<HistoryDay>) =
            (days.firstOrNull { it.date == date }?.value as? DayValue.Count)?.value

        return DayRecords(
            date = date,
            steps = count(totals.steps(daySport)),
            calories = count(totals.calories(daySport)),
            distanceMeters = if (daySport.isEmpty()) null else daySport.sumOf { it.distanceMeters.toDouble() }.roundToInt(),
            activeMinutes = if (daySport.isEmpty()) null else daySport.sumOf { it.activeMinutes },
            halfHours = halfHours.toList(),
            heart = dayHeart.map { HeartPoint(it.timestampEpoch, it.bpm) },
            sleep = sleep,
            night = totals.sleep(daySessions, dayStages).firstOrNull { it.date == date }?.value as? DayValue.Night,
        )
    }

    /** The heart-rate chart's slots: each reading at its five minutes; two in one slot show as their range. */
    fun heartSlots(points: List<HeartPoint>): List<IntRange?> {
        val slots = arrayOfNulls<IntRange>(HEART_SLOTS)
        points.forEach { p ->
            val i = heartSlotOf(p.atMillis).coerceIn(0, HEART_SLOTS - 1)
            val r = slots[i]
            slots[i] = if (r == null) p.bpm..p.bpm else minOf(r.first, p.bpm)..maxOf(r.last, p.bpm)
        }
        return slots.toList()
    }
}

/** Where a day can go: back while there is any record before it, forward up to today. */
fun canGoNext(date: LocalDate, today: LocalDate): Boolean = date < today

/**
 * Which way a finished horizontal swipe of [dx] pixels moves, once past [threshold]: -1 to
 * the day before, +1 to the day after, 0 not at all. Days run with the reading direction, as
 * a page does: in a left-to-right layout the earlier day lies to the left, so dragging the
 * page to the right (dx > 0) brings it in; right to left, the reverse.
 */
fun swipeDirection(dx: Float, threshold: Float, rtl: Boolean): Int = when {
    dx > threshold -> if (rtl) 1 else -1
    dx < -threshold -> if (rtl) -1 else 1
    else -> 0
}
