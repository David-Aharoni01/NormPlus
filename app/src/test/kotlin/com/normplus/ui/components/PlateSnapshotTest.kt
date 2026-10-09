package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The reversed plate as the screens use it: the watch card on Watch (with a fix-it under it),
 * and the confirm step of the firmware flow. Material components on it come out reversed.
 */
@RunWith(Parameterized::class)
class PlateSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun watchCard() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                Plate(Modifier.fillMaxWidth()) {
                    Text("Norm 2", style = MaterialTheme.typography.titleLarge)
                    QuietStatusLine("Connected · synced 14:32")
                    Spacer(Modifier.height(NormPlusTheme.spacing.l))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                        DateNumeral("82%", style = NormPlusTheme.type.numeralMedium, fitToWidth = false)
                        QuietStatusLine("battery", mark = StatusMark.Charging)
                    }
                    Spacer(Modifier.height(NormPlusTheme.spacing.l))
                    OutlinedButton(onClick = {}) { Text("Find watch") }
                }
                FixItCard(
                    title = "Notification access is off",
                    reason = "Norm+ needs it to send your notifications to the watch.",
                    actionLabel = "Turn on",
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun confirmStep() = paparazzi.snapshot {
        ComponentFrame {
            Plate(Modifier.fillMaxWidth()) {
                Text("Send the original screens and fonts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(NormPlusTheme.spacing.s))
                Text(
                    "The watch shows nothing until this finishes. Keep the phone close, with Norm+ open.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NormPlusTheme.spacing.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = {})
                    Text(
                        "I understand the watch shows nothing until this finishes, and stays blank if it's interrupted",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(NormPlusTheme.spacing.m))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            }
        }
    }
}
