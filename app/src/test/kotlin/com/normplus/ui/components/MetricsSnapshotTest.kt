package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.ui.Modifier
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The day's numbers: the steps hero card on its glow (in progress, goal reached, 40,000, and
 * not reported), the metric tiles in their grid (one absent), and the rows that open a day.
 */
@RunWith(Parameterized::class)
class MetricsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun heroInProgress() = paparazzi.snapshot {
        ComponentFrame {
            StepsHeroCard(6412, 8000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth().heroGlow())
        }
    }

    @Test
    fun heroGoalReached() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
                StepsHeroCard(9120, 8000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth().heroGlow())
                // The widest figure the brief allows, still on one line at font scale 1.3.
                StepsHeroCard(40000, 20000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth())
            }
        }
    }

    @Test
    fun heroAbsentAndAsOf() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
                StepsHeroCard(null, 8000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth())
                StepsHeroCard(3020, 8000, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.fillMaxWidth(), note = "as of 09:12")
            }
        }
    }

    @Test
    fun tiles() = paparazzi.snapshot {
        ComponentFrame {
            MetricGrid {
                MetricTile(
                    "Sleep", Icons.Rounded.Bedtime, durationFigure(408), "Sleep, 6 hours 48 minutes",
                    detail = "deep 1 h 32 m · 23:41–06:29",
                )
                MetricTile(
                    "Heart rate", Icons.Rounded.Favorite, figure("72" to "bpm"), "Heart rate, 72 bpm at 14:05",
                    detail = "at 14:05",
                )
                MetricTile("Calories", Icons.Rounded.LocalFireDepartment, figure("1,840" to "kcal"), "Calories, 1,840 kcal")
                MetricTile(
                    "Distance", Icons.Rounded.Route, figure("4.6" to "km"), "Distance, 4.6 km, active 52 minutes",
                    detail = "active 52 min",
                )
                MetricTile("Sleep", Icons.Rounded.Bedtime, null, "Sleep, none recorded", absentText = "No sleep recorded")
                MetricTile("Heart rate", Icons.Rounded.Favorite, null, "Heart rate, no reading yet", absentText = "No reading yet")
            }
        }
    }

    @Test
    fun dayRows() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                DayRow("WED", "8", "Yesterday", "9,120 steps", goalReached = true, spoken = "Yesterday, Wednesday 8 October, 9,120 steps, goal reached", onClick = {})
                DayRow("TUE", "7", "Tuesday", "3,020 steps · 1,120 kcal", goalReached = false, spoken = "Tuesday 7 October, 3,020 steps", onClick = {})
            }
        }
    }
}
