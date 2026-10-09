package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The state marks alone, and the settings rows that carry them, in every send state. */
@RunWith(Parameterized::class)
class SettingsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun stateMarks() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
                DaySheet(perforated = false) {
                    Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                        SendState.entries.forEach { StateMark(it) }
                    }
                }
                Plate {
                    Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                        SendState.entries.forEach { StateMark(it) }
                    }
                }
            }
        }
    }

    @Test
    fun section() = paparazzi.snapshot {
        ComponentFrame {
            Column {
                SettingsSection("Watch") {
                    SettingRow("Raise to wake", supporting = "Lights the screen when you lift your wrist", sendState = SendState.Sent, trailing = { Switch(true, {}) })
                    SettingsDivider()
                    SettingRow("Brightness", supporting = "Level 4 of 5", sendState = SendState.Sending)
                    SettingsDivider()
                    SettingRow("Vibration", supporting = "Pulse", sendState = SendState.NotSent, onRetry = {}, onDismiss = {})
                    SettingsDivider()
                    SettingRow("Do not disturb", supporting = "22:00 to 07:00", sendState = SendState.Waiting, trailing = { Switch(false, {}) })
                    SettingsDivider()
                    SettingRow("Screen timeout", supporting = "10 seconds", asOf = "09:12")
                }
                SettingsSection("Health") {
                    SettingRow("Heart-rate monitor", supporting = "Every 30 minutes", enabled = false, trailing = { Switch(true, {}, enabled = false) })
                }
            }
        }
    }
}
