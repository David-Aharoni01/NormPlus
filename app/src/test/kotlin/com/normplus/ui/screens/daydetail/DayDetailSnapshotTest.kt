package com.normplus.ui.screens.daydetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.status.Link
import com.normplus.status.WatchBattery
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.sin

/** Day detail in its frame (the header with Back), with made-up records shaped like `normwatch records` data. */
@RunWith(Parameterized::class)
class DayDetailSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        val zone: ZoneId = ZoneId.systemDefault()
        val today: LocalDate = LocalDate.of(2026, 10, 9)
        val past: LocalDate = today.minusDays(3)
        private val synced = at(today, 14, 32)
        val ready = WatchStatus(link = Link.Ready, battery = WatchBattery(82, false, synced), lastSyncEpochMs = synced)
        private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
        val clock: (Long) -> String = { hhmm.format(Instant.ofEpochMilli(it).atZone(zone)) }

        fun at(date: LocalDate, h: Int, m: Int, s: Int = 0): Long =
            LocalDateTime.of(date, LocalTime.of(h, m, s)).atZone(zone).toInstant().toEpochMilli()

        /** Half-hour records, stamped at :29 and :59 (the day's last at 23:58:30) up to [untilHalfHour]; quiet hours skipped. */
        fun sport(date: LocalDate, scale: Double, untilHalfHour: Int = 48): List<SportEntity> =
            (0 until untilHalfHour).mapNotNull { i ->
                if (i in 2..11 || i == 30) return@mapNotNull null // asleep, and a half hour with no record
                val t = if (i == 47) at(date, 23, 58, 30) else at(date, i / 2, if (i % 2 == 0) 29 else 59)
                val awake = i in 13..45
                val steps = if (!awake || i == 25) 0 else (abs(sin(i * 0.9)) * 900 * scale + (i % 4) * 60).toInt()
                SportEntity(
                    timestampEpoch = t, steps = steps, calories = steps * 0.045f + 1.5f, distanceMeters = steps * 0.72f,
                    avgHeartRate = 0, sportType = 0, activeMinutes = if (steps > 300) steps / 90 else 0,
                )
            }

        fun heart(date: LocalDate, n: Int): List<HeartRateEntity> = (0 until n).map { k ->
            val minute = 60 + k * (22 * 60 / n)
            HeartRateEntity(timestampEpoch = at(date, minute / 60, minute % 60), bpm = 56 + ((sin(k * 0.7) + 1) * 32).toInt() + k % 5)
        }

        /** A night from 23:41 to 06:29 in stages, and (with [nap]) an afternoon nap ending the same day. */
        fun sleep(date: LocalDate, nap: Boolean): Pair<List<SleepSessionEntity>, List<SleepStageEntity>> {
            val start = at(date.minusDays(1), 23, 41)
            val end = at(date, 6, 29)
            val pattern = listOf(2 to 9, 1 to 38, 0 to 52, 1 to 64, 2 to 4, 1 to 47, 0 to 41, 1 to 70, 2 to 6, 1 to 36, 0 to 20, 1 to 21)
            var t = start
            val stages = pattern.map { (stage, minutes) ->
                SleepStageEntity(sessionId = 1, timestampEpoch = t, stage = stage, durationSeconds = minutes * 60).also { t += minutes * 60_000L }
            }
            val sessions = mutableListOf(SleepSessionEntity(id = 1, startEpoch = start, endEpoch = end))
            val all = stages.toMutableList()
            if (nap) {
                val ns = at(date, 15, 5)
                sessions += SleepSessionEntity(id = 2, startEpoch = ns, endEpoch = ns + 41 * 60_000L)
                all += SleepStageEntity(sessionId = 2, timestampEpoch = ns, stage = 2, durationSeconds = 6 * 60)
                all += SleepStageEntity(sessionId = 2, timestampEpoch = ns + 6 * 60_000L, stage = 1, durationSeconds = 35 * 60)
            }
            return sessions to all
        }

        fun day(date: LocalDate, scale: Double = 1.0, readings: Int = 18, withSleep: Boolean = true, nap: Boolean = false, until: Int = 48, cutoff: Long = Long.MAX_VALUE): DayRecords {
            val (sessions, stages) = if (withSleep) sleep(date, nap) else emptyList<SleepSessionEntity>() to emptyList()
            return DayBuilder(zone).build(date, sport(date, scale, until), heart(date, readings).filter { it.timestampEpoch < cutoff }, sessions, stages)
        }

        fun state(date: LocalDate, day: DayRecords?, failed: Boolean = false, goal: Int = 8_000) = DayDetailUiState(
            date = date, today = today, goal = goal, loaded = true, day = day, hasEarlier = true, failed = failed,
        )
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    /** A full day over its goal: the hero and the tiles. (The cards below them: [DayDetailCardsSnapshotTest].) */
    @Test
    fun goalDay() = paparazzi.snapshot { Screen(state(past, day(past, scale = 0.6))) }

    /** With a nap that ended the same day, the sleep tile counts the sessions. */
    @Test
    fun withNap() = paparazzi.snapshot { Screen(state(past, day(past, scale = 0.45, nap = true))) }

    /** Yesterday under its goal, with no sleep and no heart rate. */
    @Test
    fun noSleepNoHeartRate() = paparazzi.snapshot {
        val y = today.minusDays(1)
        Screen(state(y, day(y, scale = 0.25, readings = 0, withSleep = false)))
    }

    /** A day the watch synced nothing for. */
    @Test
    fun nothingSynced() = paparazzi.snapshot {
        val d = today.minusDays(40)
        Screen(state(d, DayBuilder(zone).build(d, emptyList(), emptyList(), emptyList(), emptyList())))
    }

    /** A day in another year: the year in the title and the date line. */
    @Test
    fun anotherYear() = paparazzi.snapshot {
        val d = LocalDate.of(2025, 12, 30)
        Screen(state(d, day(d, scale = 0.5, readings = 4, withSleep = false)))
    }

    /** Today's date, still filling: the records so far, as of the last sync. */
    @Test
    fun todayStillFilling() = paparazzi.snapshot {
        Screen(state(today, day(today, scale = 0.5, readings = 18, until = 29, cutoff = synced)))
    }

    @Test
    fun readFailed() = paparazzi.snapshot { Screen(state(past, null, failed = true)) }

    @Test
    fun rightToLeft() = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Screen(state(past, day(past, scale = 0.6, nap = true)))
        }
    }

    @Composable
    private fun Screen(state: DayDetailUiState) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides ready,
                LocalShellStatus provides shellStatus(ready, onFix = {}, today = today),
            ) {
                DayDetailContent(
                    state = state, lastSyncEpochMs = synced, onBack = {}, onPrevious = {}, onNext = {}, onRetry = {},
                    clock = clock, zone = zone,
                )
            }
        }
    }
}
