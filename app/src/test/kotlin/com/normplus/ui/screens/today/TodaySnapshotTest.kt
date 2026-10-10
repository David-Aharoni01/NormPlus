package com.normplus.ui.screens.today

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.domain.usecase.SyncStage
import com.normplus.status.Link
import com.normplus.status.SyncProblem
import com.normplus.status.SyncState
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellSamples
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Today's states on one fixed day, 9 October 2026 (ShellSamples.today). */
private object TodaySamples {
    val day = ShellSamples.today

    fun at(daysBack: Long, hour: Int, minute: Int): Long =
        LocalDateTime.of(day.minusDays(daysBack), LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val sleep = SleepSummary(asleepMinutes = 408, deepMinutes = 92, startEpochMs = at(1, 23, 41), endEpochMs = at(0, 6, 29))
    val heart = HeartReading(72, at(0, 14, 5))

    /** Connected, read just now, 6,412 of 8,000. */
    val live = TodayUiState(
        date = day,
        goal = 8_000,
        steps = 6_412,
        calories = 1_840,
        distanceMeters = 4_630,
        activeMinutes = 52,
        sleep = sleep,
        heartRate = heart,
        yesterday = DaySteps(day.minusDays(1), 9_120),
        canSync = true,
    )
}

/**
 * Today (#99) on the whole Pixel 8 screen, in the shell's frame: every state the brief lists
 * (§5), in light, dark and font scale 1.3, and right to left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class TodaySnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun nothingSyncedYet() = paparazzi.snapshot {
        Today(
            TodayUiState(date = TodaySamples.day, goal = 8_000, nothingSynced = true, canSync = true),
            ShellSamples.ready.copy(lastSyncEpochMs = null),
        )
    }

    @Test
    fun live() = paparazzi.snapshot { Today(TodaySamples.live, ShellSamples.ready) }

    /** Not connected: the last known figures "as of 09:12", the banner in the pill's place. */
    @Test
    fun lastKnown() = paparazzi.snapshot {
        Today(
            TodaySamples.live.copy(asOfEpochMs = TodaySamples.at(0, 9, 12), canSync = false),
            ShellSamples.ready.copy(link = Link.Disconnected),
        )
    }

    @Test
    fun syncing() = paparazzi.snapshot {
        Today(
            TodaySamples.live.copy(syncing = true, canSync = false),
            ShellSamples.ready.copy(sync = SyncState.Running(SyncStage.Sport, 412, 922)),
        )
    }

    @Test
    fun syncStoppedShort() = paparazzi.snapshot {
        val at = TodaySamples.at(0, 14, 32)
        Today(
            TodaySamples.live.copy(shortfall = SyncShortfall(at, 900, 922)),
            ShellSamples.ready.copy(sync = SyncState.Finished(at, listOf(SyncProblem(SyncStage.Sport, 900, 922, "timeout")))),
        )
    }

    /** Goal met, today and yesterday; 40,000 must fit at font scale 1.3. */
    @Test
    fun goalMet() = paparazzi.snapshot {
        Today(TodaySamples.live.copy(steps = 40_000, goal = 10_000, yesterday = DaySteps(TodaySamples.day.minusDays(1), 12_480)), ShellSamples.ready)
    }

    /** Morning, nothing slept on record and no heart-rate reading yet. */
    @Test
    fun noSleepNoHeartRate() = paparazzi.snapshot {
        Today(
            TodaySamples.live.copy(steps = 312, calories = 64, distanceMeters = 220, activeMinutes = 3, sleep = null, heartRate = null, yesterday = DaySteps(TodaySamples.day.minusDays(1), null)),
            ShellSamples.ready,
        )
    }

    /** The watch's own summary only: sleep without its span, heart rate without its time; calories not reported. */
    @Test
    fun watchSummaryOnly() = paparazzi.snapshot {
        Today(
            TodaySamples.live.copy(sleep = SleepSummary(395, null, null, null), heartRate = HeartReading(68, null), calories = null, distanceMeters = null),
            ShellSamples.ready,
        )
    }

    @Test
    fun rightToLeft() = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Today(TodaySamples.live, ShellSamples.ready)
        }
    }

    @Composable
    private fun Today(state: TodayUiState, status: WatchStatus) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                TabsScaffold(
                    selected = ShellTab.Today,
                    onSelect = {},
                    syncEnabled = state.canSync,
                    onSync = {},
                ) { padding ->
                    TodayContent(
                        state = state,
                        contentPadding = padding,
                        onSync = {},
                        onDismissShortfall = {},
                        onOpenDay = {},
                    )
                }
            }
        }
    }
}
