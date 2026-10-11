package com.normplus.ui.screens.watchscreens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.normplus.status.Link
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.components.SendState
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellSamples
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The watch's screens order (#103) in light, dark and font scale 1.3. */
@RunWith(Parameterized::class)
class WatchScreensSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        private val live = WatchScreensUiState(connected = true)
        private val moved = live.copy(order = listOf(3, 1, 6, 9, 2, 7, 8, 4, 5, 10))
        private val time: LocalTime = LocalTime.of(10, 9)
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun live() = paparazzi.snapshot { Screen(live) }

    @Test
    fun offline() = paparazzi.snapshot {
        Screen(live.copy(connected = false), ShellSamples.ready.copy(link = Link.Disconnected))
    }

    @Test
    fun sending() = paparazzi.snapshot { Screen(moved.copy(send = SendState.Sending)) }

    @Test
    fun notSent() = paparazzi.snapshot { Screen(moved.copy(send = SendState.NotSent)) }

    @Composable
    private fun Screen(state: WatchScreensUiState, status: WatchStatus = ShellSamples.ready) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                WatchScreensContent(state = state, time = time, date = ShellSamples.today, actions = WatchScreensActions())
            }
        }
    }
}
