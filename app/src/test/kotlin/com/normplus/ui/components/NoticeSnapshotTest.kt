package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The fix-it card and the failure notice: always an icon and words in a container. */
@RunWith(Parameterized::class)
class NoticeSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun notices() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                FixItCard(
                    title = "Battery optimisation is on",
                    reason = "Android may stop Norm+ in the background, and the watch then loses its connection.",
                    actionLabel = "Allow",
                    onAction = {},
                    onDismiss = {},
                )
                Notice(
                    title = "Stopped while sending pieces",
                    tone = NoticeTone.Failed,
                    body = "The watch stays blank until the update is sent again.",
                    actionLabel = "Send again",
                    onAction = {},
                    secondaryLabel = "Details",
                    onSecondary = {},
                )
            }
        }
    }
}
