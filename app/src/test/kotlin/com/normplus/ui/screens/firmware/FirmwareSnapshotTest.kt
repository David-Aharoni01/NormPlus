package com.normplus.ui.screens.firmware

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.normplus.protocol.ota.OtaProgress
import com.normplus.protocol.ota.OtaStep
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

/** The firmware update (#106): every phase, in light, dark and font scale 1.3. */
@RunWith(Parameterized::class)
class FirmwareSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        private val ready = FirmwareUiState(watchVersion = "A0.2R0.4T0.0H0.6B0.1", connected = true)
        private val review = ready.copy(target = FirmwareTarget.OriginalResources)
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun choose() = paparazzi.snapshot { Screen(ready) }

    /** A custom file handed over from Technical sits first. */
    @Test
    fun chooseWithCustomFile() = paparazzi.snapshot {
        Screen(ready.copy(customFileUri = Uri.EMPTY, customFileName = "resources-test.bin"))
    }

    /** The build carries no resource image: the way back is shown, not offered. */
    @Test
    fun chooseWithoutBundledImage() = paparazzi.snapshot { Screen(ready.copy(bundledAvailable = false)) }

    /** The watch has just connected: Start waits, even with the box ticked. */
    @Test
    fun reviewSettling() = paparazzi.snapshot { Screen(review.copy(acknowledged = true, settleSeconds = 7)) }

    @Test
    fun reviewReady() = paparazzi.snapshot { Screen(review.copy(acknowledged = true)) }

    @Test
    fun sending() = paparazzi.snapshot {
        Screen(review.copy(acknowledged = true, isFlashing = true, progress = OtaProgress(OtaStep.DATA_STREAM, 1840, 772), remainingSeconds = 95))
    }

    @Test
    fun done() = paparazzi.snapshot { Screen(review.copy(progress = OtaProgress(OtaStep.DONE))) }

    @Test
    fun failed() = paparazzi.snapshot {
        Screen(review.copy(progress = OtaProgress(OtaStep.FAILED, errorMessage = "CRC mismatch"), error = "CRC mismatch"))
    }

    @Composable
    private fun Screen(state: FirmwareUiState, status: WatchStatus = ShellSamples.ready) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                FirmwareContent(state = state, actions = FirmwareActions())
            }
        }
    }
}
