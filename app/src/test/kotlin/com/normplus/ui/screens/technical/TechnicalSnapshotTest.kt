package com.normplus.ui.screens.technical

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.normplus.status.Blocker
import com.normplus.status.Link
import com.normplus.status.SyncProblem
import com.normplus.status.SyncState
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellSamples
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Technical (#107) in light, dark and font scale 1.3. */
@RunWith(Parameterized::class)
class TechnicalSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        private val facts = TechnicalUiState(
            serviceRunning = true,
            startOnBoot = true,
            address = "4C:59:80:12:44:F1",
            version = "A0.2R0.4T0.0H0.6B0.1",
            lastConnectedEpochMs = ShellSamples.syncedToday,
            sportRecords = 975,
            heartRateRecords = 272,
            sleepSessions = 2,
        )
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun running() = paparazzi.snapshot {
        Screen(facts, ShellSamples.ready.copy(sync = SyncState.Finished(ShellSamples.syncedToday, emptyList())))
    }

    /** The service stopped from its notification: Start on its row, the banner above. */
    @Test
    fun serviceStopped() = paparazzi.snapshot {
        Screen(
            facts.copy(serviceRunning = false),
            ShellSamples.ready.copy(
                link = Link.Disconnected,
                blockers = listOf(Blocker.ServiceStopped),
                sync = SyncState.Finished(ShellSamples.syncedToday, listOf(SyncProblem(null, 900, 922, "stream idle timeout"))),
            ),
        )
    }

    @Composable
    private fun Screen(state: TechnicalUiState, status: WatchStatus) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                TechnicalContent(state = state, status = status, actions = TechnicalActions(), clock = { "14:32" })
            }
        }
    }
}
