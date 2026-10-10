package com.normplus.ui.screens.calibration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.normplus.status.Link
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
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

/**
 * Hands calibration (#105) on the whole Pixel 8 screen: every state the brief lists (§6), in
 * light, dark and font scale 1.3.
 */
@RunWith(Parameterized::class)
class HandsCalibrationSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        private val connected = CalibrationUiState(isConnected = true)
        private val time: LocalTime = LocalTime.of(14, 32)
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun notConnected() = paparazzi.snapshot {
        Screen(CalibrationUiState(isConnected = false), ShellSamples.ready.copy(link = Link.Disconnected))
    }

    @Test
    fun minuteHand() = paparazzi.snapshot { Screen(connected) }

    @Test
    fun hourHand() = paparazzi.snapshot { Screen(connected.copy(stepIndex = 1)) }

    @Test
    fun saving() = paparazzi.snapshot { Screen(connected.copy(stepIndex = 1, saving = true)) }

    @Test
    fun done() = paparazzi.snapshot { Screen(connected.copy(stepIndex = 1, done = true)) }

    /** The link dropped during the sweep: say so, and start again once it is back. */
    @Test
    fun saveFailed() = paparazzi.snapshot {
        Screen(
            CalibrationUiState(isConnected = false, stepIndex = 1, saveFailed = true),
            ShellSamples.ready.copy(link = Link.Disconnected),
        )
    }

    @Composable
    private fun Screen(state: CalibrationUiState, status: WatchStatus = ShellSamples.ready) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                HandsCalibrationContent(state = state, time = time, actions = CalibrationActions())
            }
        }
    }
}
