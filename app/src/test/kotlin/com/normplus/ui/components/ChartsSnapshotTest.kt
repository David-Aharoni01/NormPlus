package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.math.sin

/**
 * The charts with made-up records (shaped like `normwatch records` data): every record a bar,
 * gaps left empty, zeros as stubs, goal days green against the goal line.
 */
@RunWith(Parameterized::class)
class ChartsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    private val week = listOf("Fri", "Sat", "Sun", "Mon", "Tue", "Wed", "Thu")
    private val minute = 60_000L

    @Test
    fun stepsByHalfHour() = paparazzi.snapshot {
        // 48 half hours: nothing at night, no records at all 03:00-05:00 (the watch was off).
        val bars = (0 until 48).map { slot ->
            when {
                slot in 6..9 -> null
                slot < 14 -> ChartBar(0f)
                else -> ChartBar((abs(sin(slot / 3.0)) * 900 + (if (slot == 36) 1500 else 0)).toFloat())
            }
        }
        Sheets {
            OnSheet("Steps by the half hour") {
                BarChart(bars, "Steps by the half hour", axisLabels = listOf("00:00", "06:00", "12:00", "18:00", "24:00"))
            }
        }
    }

    @Test
    fun stepsByDay() = paparazzi.snapshot {
        val steps = listOf(5310, 9120, 7400, 12040, 3020, null, 6412)
        Sheets {
            OnSheet("Steps, week") {
                BarChart(
                    bars = steps.map { s -> s?.let { ChartBar(it.toFloat(), goalMet = it >= 8000) } },
                    contentDescription = "Steps by day",
                    goal = 8000f,
                    goalLabel = "8,000",
                    axisLabels = week,
                )
            }
        }
    }

    @Test
    fun stepsByMonthAndQuarter() = paparazzi.snapshot {
        fun days(n: Int) = (0 until n).map { d ->
            if (d % 17 == 5) null
            else (6000 + sin(d * 1.7) * 3500 + (d % 5) * 400).toFloat().let { ChartBar(it, goalMet = it >= 8000f) }
        }
        Sheets {
            OnSheet("Steps, month") {
                BarChart(days(30), "Steps by day, month", goal = 8000f, goalLabel = "8,000", axisLabels = listOf("10 Sep", "24 Sep", "9 Oct"))
            }
            OnSheet("Steps, 3 months") {
                BarChart(days(91), "Steps by day, 3 months", goal = 8000f, goalLabel = "8,000", axisLabels = listOf("July", "August", "September", "October"))
            }
        }
    }

    @Test
    fun heartRate() = paparazzi.snapshot {
        val days = listOf(Triple(52, 138, 71), Triple(55, 121, 69), null, Triple(49, 152, 74), Triple(57, 117, 70), Triple(51, 129, 72), Triple(54, 133, 73))
        val readings = (0 until 48).map { slot -> if (slot % 3 == 0 && slot > 4) 60 + (sin(slot / 4.0) * 18).toInt() else null }
        Sheets {
            OnSheet("Heart rate, week") {
                BarChart(
                    bars = days.map { d -> d?.let { ChartBar(high = it.second.toFloat(), low = it.first.toFloat(), mark = it.third.toFloat()) } },
                    contentDescription = "Heart rate by day: range and average",
                    scaleMin = 40f,
                    scaleMax = 160f,
                    axisLabels = week,
                )
            }
            OnSheet("Heart rate, a day's readings") {
                BarChart(
                    bars = readings.map { r -> r?.let { ChartBar(high = it.toFloat(), low = it.toFloat()) } },
                    contentDescription = "Heart-rate readings",
                    scaleMin = 40f,
                    scaleMax = 100f,
                    axisLabels = listOf("00:00", "12:00", "24:00"),
                )
            }
        }
    }

    @Test
    fun sleep() = paparazzi.snapshot {
        val nights = listOf(
            SleepNight(92, 296, 20), SleepNight(80, 310, 35), null, SleepNight(101, 270, 12),
            SleepNight(64, 240, 44), SleepNight(88, 300, 18), SleepNight(92, 300, 16),
        )
        val stages = listOf(
            18 to SleepStage.Light, 52 to SleepStage.Deep, 80 to SleepStage.Light, 12 to SleepStage.Awake,
            40 to SleepStage.Deep, 128 to SleepStage.Light, 8 to SleepStage.Awake, 70 to SleepStage.Light,
        )
        var at = 0L
        val spans = stages.map { (minutes, stage) -> StageSpan(at, at + minutes * minute, stage).also { at += minutes * minute } }
        Sheets {
            OnSheet("Sleep, week") {
                SleepStackChart(nights, "Sleep by night, by stage", axisLabels = week)
                Spacer(Modifier.height(NormPlusTheme.spacing.s))
                SleepLegend()
            }
            OnSheet("Last night") {
                SleepStageBand(spans, 0L, at, "Sleep stages 23:41 to 06:29", startLabel = "23:41", endLabel = "06:29")
            }
        }
    }

    @Composable
    private fun Sheets(content: @Composable () -> Unit) {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) { content() }
        }
    }

    @Composable
    private fun OnSheet(title: String, content: @Composable () -> Unit) {
        NormCard(Modifier.fillMaxWidth()) {
            Text(title, style = NormPlusTheme.type.cardTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(NormPlusTheme.spacing.l))
            content()
        }
    }
}
