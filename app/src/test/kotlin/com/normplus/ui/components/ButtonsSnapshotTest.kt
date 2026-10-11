package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.ui.Modifier
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The pill buttons in every tone, with and without an icon, enabled and disabled. */
@RunWith(Parameterized::class)
class ButtonsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun pills() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                    PillButton("Earlier", {}, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
                    PillButton("Later", {}, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                    PillButton("Start", {}, tone = PillTone.Primary)
                    PillButton("Start", {}, tone = PillTone.Primary, enabled = false)
                    PillButton("Sync", {}, icon = Icons.Rounded.Sync)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                    PillButton("Not now", {}, tone = PillTone.Neutral)
                    PillButton("Forget this watch", {}, tone = PillTone.Danger)
                    PillButton("Retry", {}, tone = PillTone.Quiet)
                }
            }
        }
    }
}
