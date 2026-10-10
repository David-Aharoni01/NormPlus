package com.normplus.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.normplus.domain.usecase.SyncStage
import com.normplus.status.Blocker
import com.normplus.status.Link
import com.normplus.status.SyncState
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LargeTitleHeader
import com.normplus.ui.components.ShellStatusSlot
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The shell's parts (#97), worded by the real status words: the header with each status the
 * pill shows, and the banner in each connection state and for each blocker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class ShellPartsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    /** The header with the pill in each of its states, Sync at the title's end on the first. */
    @Test
    fun headerWithEachStatus() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                val ready = ShellSamples.ready
                Header(ready, sync = true)
                Header(ready.copy(battery = ShellSamples.battery.copy(percent = 64, charging = true)))
                Header(ready.copy(sync = SyncState.Running(SyncStage.Sport, 412, 922)))
                Header(ready.copy(lastSyncEpochMs = null))
                // A sync on an earlier day prints the date.
                Header(ready.copy(lastSyncEpochMs = ShellSamples.yesterdayMorning))
            }
        }
    }

    /** Every pill the status can show, the offline one included (the banner hides it on screens). */
    @Test
    fun pills() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                val ready = ShellSamples.ready
                listOf(
                    ready,
                    ready.copy(battery = ShellSamples.battery.copy(percent = 64, charging = true)),
                    ready.copy(sync = SyncState.Running(SyncStage.Counting, 0, 0)),
                    ready.copy(sync = SyncState.Running(SyncStage.HeartRate, 12, 48)),
                    ready.copy(sync = SyncState.Running(SyncStage.Sleep, 2, 3)),
                    ready.copy(link = Link.Disconnected, lastSyncEpochMs = ShellSamples.yesterdayMorning),
                ).forEach { ShellStatusSlot(status = shellStatus(it, onFix = {}, today = ShellSamples.today).copy(banner = null)) }
            }
        }
    }

    @Test
    fun bannersForTheConnection() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                val states = listOf(
                    Link.Scanning, Link.Connecting, Link.SettingUp,
                    Link.Reconnecting(0), Link.Reconnecting(2), Link.Reconnecting(3), Link.Disconnected,
                )
                for (link in states) Slot(ShellSamples.ready.copy(link = link))
            }
        }
    }

    @Test
    fun bannersForTheBlockers() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                for (blocker in Blocker.entries.filter { it.inBanner }) {
                    Slot(ShellSamples.ready.copy(blockers = listOf(blocker)))
                }
            }
        }
    }

    @Composable
    private fun Slot(status: WatchStatus) = ShellStatusSlot(status = shellStatus(status, onFix = {}, today = ShellSamples.today))

    @Composable
    private fun Header(status: WatchStatus, sync: Boolean = false) {
        val shell = shellStatus(status, onFix = {}, today = ShellSamples.today)
        LargeTitleHeader(
            title = "Today",
            windowInsets = WindowInsets(0),
            actions = {
                if (sync) IconButton(onClick = {}) { Icon(Icons.Rounded.Sync, contentDescription = null) }
            },
            status = { ShellStatusSlot(status = shell) },
        )
    }
}
