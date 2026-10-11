package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import java.time.LocalTime

/**
 * The signature: the watch drawn live. The dial pager on the face and on a page, the hands
 * for calibration, every screen's preview, and the thumbnails of the screens' order (#103).
 */
@RunWith(Parameterized::class)
class DialSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    private val time = LocalTime.of(10, 9)
    private val date = LocalDate.of(2026, 10, 9)
    // The watch's default order (WatchSettings.pageOrder).
    private val screens = WatchScreen.inWatchOrder(listOf(1, 3, 6, 9, 2, 7, 8, 4, 5, 10))

    @Test
    fun pagerOnTheFace() = paparazzi.snapshot {
        ComponentFrame {
            DialPager(
                screens = screens,
                time = time,
                date = date,
                state = rememberPagerState { screens.size },
                modifier = Modifier.fillMaxWidth(),
                caption = { Text("Always first", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }

    @Test
    fun pagerOnAPage() = paparazzi.snapshot {
        ComponentFrame {
            DialPager(
                screens = screens,
                time = time,
                date = date,
                state = rememberPagerState(initialPage = 2) { screens.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Test
    fun calibrationHands() = paparazzi.snapshot {
        ComponentFrame {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                WatchDial(LocalTime.of(12, 0), Modifier.size(170.dp).heroGlow(), highlight = DialHand.Minute, showTwelveMark = true)
                WatchDial(LocalTime.of(4, 0), Modifier.size(170.dp), highlight = DialHand.Hour, showTwelveMark = true)
            }
        }
    }

    @Test
    fun screenPreviews() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                WatchScreen.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                        row.forEach { WatchDial(time, Modifier.size(88.dp), screen = it, date = date, ring = false) }
                    }
                }
            }
        }
    }

    @Test
    fun thumbnails() = paparazzi.snapshot {
        ComponentFrame {
            NormCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                    screens.take(8).chunked(4).forEachIndexed { r, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            row.forEachIndexed { i, screen ->
                                val n = r * 4 + i + 1
                                WatchScreenThumbnail(screen, n, selected = n == 1, time = time, date = date, size = 62.dp, onClick = {})
                            }
                        }
                    }
                }
            }
        }
    }
}
