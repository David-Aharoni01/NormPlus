package com.normplus.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.domain.usecase.SyncStage
import com.normplus.status.Blocker
import com.normplus.status.Link
import com.normplus.status.SyncState
import com.normplus.status.WatchBattery
import com.normplus.status.WatchStatus
import com.normplus.ui.components.FlowScaffold
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.components.MetricGrid
import com.normplus.ui.components.MetricTile
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.components.StepsHeroCard
import com.normplus.ui.components.durationFigure
import com.normplus.ui.components.figure
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** What the screenshot tests print: one fixed day, so the goldens do not move with the calendar. */
internal object ShellSamples {
    val today: LocalDate = LocalDate.of(2026, 10, 9)

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val syncedToday = at(today, 14, 32)
    val yesterdayMorning = at(today.minusDays(1), 9, 12)
    val battery = WatchBattery(percent = 82, charging = false, readAtEpochMs = syncedToday)
    val ready = WatchStatus(link = Link.Ready, battery = battery, lastSyncEpochMs = syncedToday)
}

/**
 * The shell on the whole Pixel 8 screen (#97): each tab's frame (the large title with the pill
 * or the banner in its place, Sync on Today, the floating navigation capsule over the content),
 * a screen below a tab, and a flow. The content is a stand-in drawn with the shared
 * components; each tab's real content is its own issue's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class ShellSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun todayConnected() = paparazzi.snapshot { Tabs(ShellTab.Today, ShellSamples.ready) }

    @Test
    fun todaySyncing() = paparazzi.snapshot {
        Tabs(ShellTab.Today, ShellSamples.ready.copy(sync = SyncState.Running(SyncStage.Sport, 412, 922)))
    }

    @Test
    fun historyReconnecting() = paparazzi.snapshot {
        Tabs(ShellTab.History, ShellSamples.ready.copy(link = Link.Reconnecting(3)))
    }

    @Test
    fun watchWithBluetoothOff() = paparazzi.snapshot {
        Tabs(ShellTab.Watch, ShellSamples.ready.copy(link = Link.Disconnected, blockers = listOf(Blocker.BluetoothOff)))
    }

    /**
     * Right to left: the title, the banner and its action, and the capsule mirror. Without the
     * tiles, whose figures and units do not yet mirror (MetricTile's to fix, not the shell's).
     */
    @Test
    fun todayRightToLeft() = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Tabs(ShellTab.Today, ShellSamples.ready.copy(blockers = listOf(Blocker.BatteryOptimisationOn)), tiles = false)
        }
    }

    @Test
    fun screenBelowATab() = paparazzi.snapshot {
        Provided(ShellSamples.ready) {
            ScreenScaffold(title = "Notification apps", onBack = {}) { padding -> StandIn(padding) }
        }
    }

    @Test
    fun screenBelowATabDisconnected() = paparazzi.snapshot {
        Provided(ShellSamples.ready.copy(link = Link.Disconnected)) {
            ScreenScaffold(title = "Notification apps", onBack = {}) { padding -> StandIn(padding) }
        }
    }

    @Test
    fun flowWithTheBanner() = paparazzi.snapshot {
        Provided(ShellSamples.ready.copy(link = Link.Reconnecting(1))) {
            FlowScaffold(
                title = "Calibrate hands",
                onClose = {},
                step = "Minute hand",
                actions = {
                    PillButton(text = "Back", onClick = {}, tone = PillTone.Quiet)
                    PillButton(text = "Next", onClick = {}, tone = PillTone.Primary)
                },
            ) {
                Text("Turn the minute hand until it points at twelve.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun Provided(status: WatchStatus, content: @Composable () -> Unit) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
                content = content,
            )
        }
    }

    @Composable
    private fun Tabs(tab: ShellTab, status: WatchStatus, tiles: Boolean = true) {
        Provided(status) {
            TabsScaffold(
                selected = tab,
                onSelect = {},
                syncEnabled = status.isReady && !status.isSyncing,
                onSync = {},
            ) { padding -> StandIn(padding, tiles) }
        }
    }

    /** A tab's content, drawn with the shared components, scrolling under the capsule. */
    @Composable
    private fun StandIn(padding: PaddingValues, tiles: Boolean = true) {
        val spacing = NormPlusTheme.spacing
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = padding.calculateTopPadding() + spacing.s,
                bottom = padding.calculateBottomPadding() + spacing.l,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.l),
        ) {
            item { StepsHeroCard(6412, 8000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth().heroGlow()) }
            if (tiles) item {
                MetricGrid {
                    MetricTile("Sleep", Icons.Rounded.Bedtime, durationFigure(408), "Sleep, 6 hours 48 minutes", detail = "deep 1 h 32 m")
                    MetricTile("Heart rate", Icons.Rounded.Favorite, figure("72" to "bpm"), "Heart rate, 72 bpm", detail = "at 14:05")
                    MetricTile("Calories", Icons.Rounded.LocalFireDepartment, figure("1,840" to "kcal"), "Calories, 1,840 kcal")
                    MetricTile("Distance", Icons.Rounded.Route, figure("4.6" to "km"), "Distance, 4.6 km", detail = "active 52 min")
                }
            }
        }
    }
}
