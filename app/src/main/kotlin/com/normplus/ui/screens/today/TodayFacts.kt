package com.normplus.ui.screens.today

import androidx.compose.runtime.Immutable
import com.normplus.status.SyncState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * What Today shows, as plain values: the watch's own today summary (0x57), last night's sleep,
 * the latest heart-rate reading, yesterday, and the sync's shortfall. Pure, so it is tested on
 * the JVM; TodayViewModel fills it, TodayContent prints it.
 */

/**
 * One reading of the watch's own today summary, the totals it shows on its face
 * (DeviceDisplayData.smali, cmd 0x57: [0] step [1] calorie [2] distance [3] sleep
 * [4] sportTime [5] heartRate, 4-byte LE ints). A field the reply did not carry is null:
 * absent, never zero. The watch may send fewer than seven fields (DeviceDisplayCommand).
 *
 * @property readAtEpochMs when Norm+ read it.
 */
@Immutable
data class TodaySummary(
    val readAtEpochMs: Long,
    val steps: Int?,
    val calories: Int?,
    val distanceMeters: Int?,
    val sleepMinutes: Int?,
    val activeMinutes: Int?,
    val heartRate: Int?,
) {
    /** The day it counts, in [zone]: a summary from yesterday says nothing about today. */
    fun day(zone: ZoneId): LocalDate = Instant.ofEpochMilli(readAtEpochMs).atZone(zone).toLocalDate()

    /** For WatchPreferences: the time, then each field, empty when absent. */
    fun encode(): String = listOf(readAtEpochMs, steps, calories, distanceMeters, sleepMinutes, activeMinutes, heartRate)
        .joinToString(",") { it?.toString() ?: "" }

    companion object {
        /** The number of fields Today reads, in the reply's order. */
        private const val FIELDS = 6

        /**
         * From the reply's payload: every whole 4-byte field it carries. A payload with no
         * whole field is no reading at all (null). A heart rate of 0 is the watch's "no reading
         * yet", not a pulse of zero.
         */
        fun fromPayload(payload: ByteArray, readAtEpochMs: Long): TodaySummary? {
            val count = payload.size / 4
            if (count == 0) return null
            fun field(i: Int): Int? = if (i < minOf(count, FIELDS)) {
                (payload[i * 4].toInt() and 0xFF) or
                    ((payload[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((payload[i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((payload[i * 4 + 3].toInt() and 0xFF) shl 24)
            } else {
                null
            }
            return TodaySummary(
                readAtEpochMs = readAtEpochMs,
                steps = field(0),
                calories = field(1),
                distanceMeters = field(2),
                sleepMinutes = field(3),
                activeMinutes = field(4),
                heartRate = field(5)?.takeIf { it > 0 },
            )
        }

        /** [encode]'s inverse; null for anything it did not write. */
        fun decode(encoded: String?): TodaySummary? {
            val parts = encoded?.split(",") ?: return null
            if (parts.size != FIELDS + 1) return null
            val at = parts[0].toLongOrNull() ?: return null
            fun f(i: Int): Int? = parts[i].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return TodaySummary(at, f(1), f(2), f(3), f(4), f(5), f(6))
        }
    }
}

/**
 * Last night's sleep: [asleepMinutes] (deep and light, the official app's total), [deepMinutes],
 * from the first session's start to the last one's end. [startEpochMs] and [endEpochMs] are null
 * when the figure is the watch's own summary rather than synced sessions.
 */
@Immutable
data class SleepSummary(
    val asleepMinutes: Int,
    val deepMinutes: Int?,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
)

/** A heart-rate reading; [atEpochMs] null when it is the watch's summary, which carries no time. */
@Immutable
data class HeartReading(val bpm: Int, val atEpochMs: Long?)

/** Yesterday's row: its steps from the synced records (null when none were synced for it). */
@Immutable
data class DaySteps(val date: LocalDate, val steps: Int?)

/**
 * A sync that stopped short: [received] of [expected] records when a stream stopped short,
 * otherwise both null. [atEpochMs] identifies the sync, so a dismissal holds only for it.
 */
@Immutable
data class SyncShortfall(val atEpochMs: Long, val received: Int?, val expected: Int?)

object TodayFacts {
    /** A live reading older than this is shown "as of" its time, even while connected. */
    const val LIVE_FOR_MS = 15 * 60_000L

    /**
     * Last night from its sessions (up to three make one night, brief §5): asleep is deep plus
     * light, the official app's total (SleepBreakdown); from the first start to the last end.
     * [stages] are (SleepStage code, seconds): 0 deep, 1 light, 2 awake (#90).
     */
    fun sleepOf(starts: List<Long>, ends: List<Long>, stages: List<Pair<Int, Int>>): SleepSummary {
        val deep = stages.filter { it.first == 0 }.sumOf { it.second }
        val light = stages.filter { it.first == 1 }.sumOf { it.second }
        return SleepSummary(
            asleepMinutes = (deep + light) / 60,
            deepMinutes = deep / 60,
            startEpochMs = starts.minOrNull(),
            endEpochMs = ends.maxOrNull(),
        )
    }

    /** The bounds of [date] in [zone], as epoch milliseconds: start inclusive, end exclusive. */
    fun dayWindow(date: LocalDate, zone: ZoneId): LongRange {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until end
    }

    /** The summary to show for [date]: the stored one only when it was read that day. */
    fun summaryFor(date: LocalDate, stored: TodaySummary?, zone: ZoneId): TodaySummary? =
        stored?.takeIf { it.day(zone) == date }

    /**
     * When the figures are not live: the reading's time, shown as "as of", while the watch is
     * not connected or the reading is older than [LIVE_FOR_MS]. Null when live, or with nothing.
     */
    fun asOf(summary: TodaySummary?, connected: Boolean, nowEpochMs: Long): Long? {
        summary ?: return null
        val fresh = nowEpochMs - summary.readAtEpochMs < LIVE_FOR_MS
        return if (connected && fresh) null else summary.readAtEpochMs
    }

    /** The last sync's shortfall, unless it was complete, or [dismissedAt] is that sync. */
    fun shortfall(sync: SyncState, dismissedAt: Long?): SyncShortfall? {
        if (sync !is SyncState.Finished || sync.complete || sync.atEpochMs == dismissedAt) return null
        val counted = sync.problems.firstOrNull { it.received != null && it.expected != null }
        return SyncShortfall(sync.atEpochMs, counted?.received, counted?.expected)
    }

    /** Heart rate: the latest synced reading of today, else the watch's own summary. */
    fun heartRate(latestToday: HeartReading?, summary: TodaySummary?): HeartReading? =
        latestToday ?: summary?.heartRate?.let { HeartReading(it, null) }

    /**
     * Sleep: last night's synced sessions, else the watch's own summary when it counts any sleep.
     * While a session is still on, the watch counts 0 (#88): no sleep yet, not a night of none.
     */
    fun sleep(synced: SleepSummary?, summary: TodaySummary?): SleepSummary? =
        synced?.takeIf { it.asleepMinutes > 0 }
            ?: summary?.sleepMinutes?.takeIf { it > 0 }?.let { SleepSummary(it, null, null, null) }
}
