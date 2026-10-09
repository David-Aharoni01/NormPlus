package com.normplus.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** A full-screen flow, rendered as a whole Pixel 8 screen. */
@RunWith(Parameterized::class)
class FlowSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun flowStep() = paparazzi.snapshot {
        NormPlusTheme(animationsRemoved = true) {
            FlowScaffold(
                title = "Calibrate hands",
                step = "Step 1 of 2 · Minute hand",
                onClose = {},
                actions = {
                    TextButton(onClick = {}) { Text("Back") }
                    Button(onClick = {}) { Text("Next") }
                },
            ) {
                Text("Move the minute hand to 12", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Nudge it with − and +, or hold to turn it. The hour hand stays where it is.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateMark(SendState.Waiting)
            }
        }
    }
}
