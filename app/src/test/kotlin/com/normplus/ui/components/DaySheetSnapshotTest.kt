package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

/** The day sheet on its backing, as Today composes it: the sheet's anatomy, its states. */
@RunWith(Parameterized::class)
class DaySheetSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    private val friday = LocalDate.of(2026, 10, 9)
    /** Today's almanac, printed the way the screens print it (durations through durationText). */
    @Composable
    private fun almanac(): List<AlmanacEntry> {
        val asleep = durationText(408)
        val deep = durationText(92)
        return listOf(
            AlmanacEntry("Sleep", "$asleep · deep $deep · 23:41–06:29", spokenValue = "$asleep, deep $deep, 23:41 to 06:29"),
            AlmanacEntry("Heart rate", "72 bpm at 14:05"),
            AlmanacEntry("Calories", "1,840 kcal"),
            AlmanacEntry("Distance", "4.6 km"),
            AlmanacEntry("Active", "52 min"),
        )
    }

    @Test
    fun inProgress() = paparazzi.snapshot {
        ComponentFrame { SheetOnBacking(steps = 6412, yesterdayRed = true) }
    }

    @Test
    fun goalReached() = paparazzi.snapshot {
        ComponentFrame { SheetOnBacking(steps = 40000, yesterdayRed = false) }
    }

    @Test
    fun nothingReported() = paparazzi.snapshot {
        ComponentFrame {
            Plate(shape = RectangleShape) {
                DaySheet(Modifier.fillMaxWidth()) {
                    DateLine(friday)
                    Spacer(Modifier.height(NormPlusTheme.spacing.l))
                    DayFigure(steps = null, goal = 8000)
                    Spacer(Modifier.height(NormPlusTheme.spacing.xl))
                    Almanac(
                        listOf(
                            AlmanacEntry("Sleep", "No sleep recorded", absent = true),
                            AlmanacEntry("Heart rate", "No reading yet", absent = true),
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun SheetOnBacking(steps: Int, yesterdayRed: Boolean) {
        Plate(shape = RectangleShape) {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.hairline)) {
                DaySheet(Modifier.fillMaxWidth()) {
                    DateLine(friday)
                    Spacer(Modifier.height(NormPlusTheme.spacing.l))
                    DayFigure(steps = steps, goal = 8000)
                    Spacer(Modifier.height(NormPlusTheme.spacing.xl))
                    Almanac(almanac())
                }
                SheetEdge(
                    weekday = "Wed",
                    day = "8",
                    summary = if (yesterdayRed) "9,120 steps" else "5,310 steps",
                    redLetter = yesterdayRed,
                    contentDescription = "Yesterday",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

