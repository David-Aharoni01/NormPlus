package com.normplus.ui.screens.history

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.status.Link
import com.normplus.status.WatchBattery
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellTab
import com.normplus.ui.shell.TabsScaffold
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sin

/** History in its frame (the tabs' header and capsule), with made-up records shaped like `normwatch records` data. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class HistorySnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        val today: LocalDate = LocalDate.of(2026, 10, 9)
        private val synced = LocalDateTime.of(today, java.time.LocalTime.of(14, 32)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ready = WatchStatus(link = Link.Ready, battery = WatchBattery(82, false, synced), lastSyncEpochMs = synced)

        /** A day back from today for each of [n] days, skipping the days in [gaps]. */
        fun days(n: Int, gaps: Set<Int> = emptySet(), value: (Int) -> DayValue): List<HistoryDay> =
            (0 until n).filterNot { it in gaps }.map { HistoryDay(today.minusDays(it.toLong()), value(it)) }

        fun steps(i: Int) = DayValue.Count(if (i == 4) 0 else (6_000 + sin(i * 1.7) * 3_800 + (i % 5) * 450).toInt())
        fun pulse(i: Int) = if (i == 6) DayValue.Pulse(64, 64, 64, 1)
        else DayValue.Pulse(48 + i % 7, 112 + (abs(sin(i * 1.3)) * 40).toInt(), 68 + i % 6, 12)
        fun night(i: Int): DayValue {
            val deep = 70 + (i * 13) % 40
            val light = 250 + (i * 29) % 80
            val start = LocalDateTime.of(today.minusDays(i + 1L), java.time.LocalTime.of(23, 10 + i % 40)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return DayValue.Night(deep, light, 10 + i % 25, start, start + (deep + light + 20) * 60_000L, 1)
        }
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    private fun state(metric: HistoryMetric, range: HistoryRange, all: List<HistoryDay>, goal: Int? = null) = HistoryUiState(
        today = today, metric = metric, range = range, goal = goal, loaded = true,
        chart = chartSlots(all, range, today), rows = all.take(40), rowsComplete = false,
    )

    @Test
    fun stepsWeek() = paparazzi.snapshot {
        Screen(state(HistoryMetric.Steps, HistoryRange.Week, days(120, gaps = setOf(2, 17)) { steps(it) }, goal = 8_000))
    }

    @Test
    fun stepsThreeMonths() = paparazzi.snapshot {
        Screen(state(HistoryMetric.Steps, HistoryRange.ThreeMonths, days(120, gaps = setOf(2, 17, 40, 41, 42)) { steps(it) }, goal = 8_000))
    }

    @Test
    fun heartRateMonth() = paparazzi.snapshot {
        Screen(state(HistoryMetric.HeartRate, HistoryRange.Month, days(60, gaps = setOf(1, 9)) { pulse(it) }))
    }

    @Test
    fun sleepWeek() = paparazzi.snapshot {
        Screen(state(HistoryMetric.Sleep, HistoryRange.Week, days(40, gaps = setOf(3)) { night(it) }))
    }

    @Test
    fun caloriesWeek() = paparazzi.snapshot {
        Screen(state(HistoryMetric.Calories, HistoryRange.Week, days(40) { DayValue.Count(1_400 + (it * 137) % 900) }))
    }

    /** Nothing in the range, older days below it. */
    @Test
    fun rangeWithNoData() = paparazzi.snapshot {
        val old = days(30) { steps(it) }.map { it.copy(date = it.date.minusDays(200)) }
        Screen(state(HistoryMetric.Steps, HistoryRange.Week, old, goal = 8_000))
    }

    /** A metric with no record at all, while others have some. */
    @Test
    fun metricNeverRecorded() = paparazzi.snapshot {
        Screen(state(HistoryMetric.Sleep, HistoryRange.Week, emptyList()))
    }

    @Test
    fun nothingSyncedYet() = paparazzi.snapshot {
        Screen(HistoryUiState(today = today, loaded = true, nothingSynced = true), canSync = true)
    }

    @Test
    fun nothingSyncedOffline() = paparazzi.snapshot {
        Screen(HistoryUiState(today = today, loaded = true, nothingSynced = true), status = ready.copy(link = Link.Disconnected), canSync = false)
    }

    @Test
    fun readFailed() = paparazzi.snapshot {
        Screen(HistoryUiState(today = today, loaded = true, failed = true))
    }

    @Test
    fun stepsWeekRightToLeft() = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Screen(state(HistoryMetric.Steps, HistoryRange.Week, days(120, gaps = setOf(2)) { steps(it) }, goal = 8_000))
        }
    }

    @Composable
    private fun Screen(state: HistoryUiState, status: WatchStatus = ready, canSync: Boolean = true) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = today),
            ) {
                TabsScaffold(selected = ShellTab.History, onSelect = {}, syncEnabled = false, onSync = {}) { padding ->
                    HistoryContent(
                        state = state, contentPadding = padding, canSync = canSync,
                        onSelectMetric = {}, onSelectRange = {}, onOpenDay = {}, onLoadMore = {}, onRetry = {}, onSync = {},
                    )
                }
            }
        }
    }
}
