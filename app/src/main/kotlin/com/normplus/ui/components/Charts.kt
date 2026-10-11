package com.normplus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme
import kotlin.math.max
import kotlin.math.min

/*
 * The charts draw the records as they came (#95): one bar per record, a half hour or a day,
 * never a curve, never smoothed, never interpolated across a gap. A slot with no record is
 * left empty; a record of zero is a stub on the baseline, so the two never look alike.
 * Every chart mirrors in a right-to-left layout and is one TalkBack stop, described by the
 * screen in words (the figures are in the rows beside it).
 */

private val ChartHeight = 160.dp
private val MaxBarWidth = 20.dp
private val MinStub = 2.dp

/**
 * One record's bar. A plain bar runs from [low] (0) to [high]. A range (heart rate's daily
 * low to high) draws thin, with [mark] (the average) as a tick across it.
 *
 * @property goalMet the day met its goal: the bar prints green. The goal line it crosses is the
 *   non-colour cue, and the day's row says "Goal reached" in words.
 */
@Immutable
data class ChartBar(
    val high: Float,
    val low: Float = 0f,
    val mark: Float? = null,
    val goalMet: Boolean = false,
) {
    internal val isRange: Boolean get() = low > 0f || mark != null
}

/**
 * Bars over time: steps per half hour, steps or calories per day against the goal line,
 * heart rate per day as its range and average, a day's heart-rate readings (low = high).
 *
 * @param bars one per slot, in time order; null where no record came.
 * @param goal draws the goal line, labelled [goalLabel] at its end.
 * @param scaleMax the top of the scale; by default a little above the highest bar or goal.
 * @param axisLabels under the chart: one per bar when there are as many as bars (a week's
 *   weekdays), otherwise spread evenly from the first slot to the last ("00:00 … 24:00").
 */
@Composable
fun BarChart(
    bars: List<ChartBar?>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    goalLabel: String? = null,
    scaleMax: Float? = null,
    scaleMin: Float = 0f,
    axisLabels: List<String> = emptyList(),
    height: Dp = ChartHeight,
) {
    val c = NormPlusTheme.colors
    val print = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measurer = rememberTextMeasurer()
    val labelStyle = NormPlusTheme.type.chartLabel.copy(color = c.chartGoal)
    val hairline = NormPlusTheme.spacing.hairline
    val cornerDp = NormPlusTheme.shapes.barCorner
    val highest = bars.filterNotNull().maxOfOrNull { it.high } ?: 0f
    val top = scaleMax ?: (max(highest, goal ?: 0f) * 1.1f)
    val bottom = scaleMin
    Column(modifier.clearAndSetSemantics { this.contentDescription = contentDescription }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val label = goalLabel?.let { measurer.measure(it, labelStyle) }
            val plotTop = (label?.size?.height ?: 0) + 2.dp.toPx()
            val plotBottom = size.height - hairline.toPx()
            val span = (top - bottom).takeIf { it > 0f } ?: 1f
            fun y(v: Float) = plotBottom - ((v - bottom) / span).coerceIn(0f, 1f) * (plotBottom - plotTop)
            val slot = size.width / max(1, bars.size)
            val barW = min(slot * 0.62f, MaxBarWidth.toPx()).coerceAtLeast(1f)
            val corner = cornerDp.toPx()
            fun centre(i: Int) = ((i + 0.5f) * slot).let { if (rtl) size.width - it else it }

            baseline(c.chartAxis, hairline.toPx())
            bars.forEachIndexed { i, bar ->
                if (bar == null) return@forEachIndexed
                val ink = if (bar.goalMet) c.chartGoalMet else c.chartBar
                if (bar.isRange && bar.high == bar.low) {
                    // A single reading: a dot at its value.
                    val d = max(barW * 0.7f, 4.dp.toPx())
                    drawCircle(ink, radius = d / 2f, center = Offset(centre(i), y(bar.high)))
                    return@forEachIndexed
                }
                val w = if (bar.isRange) max(2.dp.toPx(), barW * 0.4f) else barW
                val yHigh = y(bar.high)
                val yLow = y(bar.low)
                val h = max(yLow - yHigh, MinStub.toPx())
                val rounding = if (bar.isRange) w / 2f else min(corner, w / 2f)
                drawTopRounded(ink, Offset(centre(i) - w / 2f, yLow - h), Size(w, h), rounding, roundBottom = bar.isRange)
                bar.mark?.let { m ->
                    val tickW = max(barW, w + 8.dp.toPx())
                    val tickH = 2.5.dp.toPx()
                    drawRoundRect(
                        print, topLeft = Offset(centre(i) - tickW / 2f, y(m) - tickH / 2f), size = Size(tickW, tickH),
                        cornerRadius = CornerRadius(tickH / 2f),
                    )
                }
            }
            if (goal != null) {
                val gy = y(goal)
                val dash = 4.dp.toPx()
                drawLine(
                    c.chartGoal, Offset(0f, gy), Offset(size.width, gy), strokeWidth = hairline.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f)),
                )
                if (label != null) {
                    val x = if (rtl) 0f else size.width - label.size.width
                    drawText(label, topLeft = Offset(x, gy - label.size.height - 1.dp.toPx()))
                }
            }
        }
        if (axisLabels.isNotEmpty()) AxisLabels(axisLabels, underSlots = axisLabels.size == bars.size)
    }
}

/** A night's sleep by stage, in minutes. */
@Immutable
data class SleepNight(val deepMinutes: Int, val lightMinutes: Int, val awakeMinutes: Int) {
    val totalMinutes: Int get() = deepMinutes + lightMinutes + awakeMinutes
}

/**
 * Sleep per night, stacked by stage: deep at the base, light on it, awake on top, each
 * separated by a gap so the stages part without colour.
 *
 * @param nights one per day, in order; null for a night with no session.
 */
@Composable
fun SleepStackChart(
    nights: List<SleepNight?>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    scaleMaxMinutes: Int? = null,
    axisLabels: List<String> = emptyList(),
    height: Dp = ChartHeight,
) {
    val c = NormPlusTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val hairline = NormPlusTheme.spacing.hairline
    val cornerDp = NormPlusTheme.shapes.barCorner
    val top = (scaleMaxMinutes ?: ((nights.maxOfOrNull { it?.totalMinutes ?: 0 } ?: 0) * 1.1f).toInt()).coerceAtLeast(1)
    Column(modifier.clearAndSetSemantics { this.contentDescription = contentDescription }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val plotBottom = size.height - hairline.toPx()
            val perMinute = plotBottom / top
            val slot = size.width / max(1, nights.size)
            val barW = min(slot * 0.62f, MaxBarWidth.toPx()).coerceAtLeast(1f)
            val gap = 2.dp.toPx()
            val corner = cornerDp.toPx()
            baseline(c.chartAxis, hairline.toPx())
            nights.forEachIndexed { i, night ->
                if (night == null) return@forEachIndexed
                val x = ((i + 0.5f) * slot).let { if (rtl) size.width - it else it } - barW / 2f
                var yBase = plotBottom
                val stages = listOf(night.deepMinutes to c.sleepDeep, night.lightMinutes to c.sleepLight, night.awakeMinutes to c.sleepAwake)
                val topmost = stages.indexOfLast { it.first > 0 }
                stages.forEachIndexed { k, (minutes, ink) ->
                    if (minutes <= 0) return@forEachIndexed
                    val h = max(minutes * perMinute - gap, 1f)
                    drawTopRounded(ink, Offset(x, yBase - h), Size(barW, h), if (k == topmost) min(corner, barW / 2f) else 0f)
                    yBase -= h + gap
                }
            }
        }
        if (axisLabels.isNotEmpty()) AxisLabels(axisLabels, underSlots = axisLabels.size == nights.size)
    }
}

/** A sleep stage, as the watch records it (the watch has no REM). */
enum class SleepStage {
    Deep, Light, Awake;

    companion object {
        /** From `SleepStage.code` in the records: 0 deep, 1 light, 2 awake (#90). */
        fun fromCode(code: Int): SleepStage? = when (code) {
            0 -> Deep
            1 -> Light
            2 -> Awake
            else -> null
        }
    }
}

/** One stretch of a night in one stage. */
@Immutable
data class StageSpan(val startMillis: Long, val endMillis: Long, val stage: SleepStage)

/**
 * A night as a stage band: time runs along it, and each stage has its own lane (awake on
 * top, light, deep at the base), so the stages read by position as well as by tint.
 *
 * @param startMillis / [endMillis] the session's start and end, the band's two ends.
 * @param startLabel / [endLabel] printed under the ends ("23:41", "06:29").
 */
@Composable
fun SleepStageBand(
    spans: List<StageSpan>,
    startMillis: Long,
    endMillis: Long,
    contentDescription: String,
    modifier: Modifier = Modifier,
    startLabel: String? = null,
    endLabel: String? = null,
    height: Dp = 72.dp,
) {
    val c = NormPlusTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val hairline = NormPlusTheme.spacing.hairline
    val duration = (endMillis - startMillis).coerceAtLeast(1L).toFloat()
    Column(modifier.clearAndSetSemantics { this.contentDescription = contentDescription }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val lane = size.height / 3f
            val guide = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            for (k in 1..2) {
                drawLine(c.chartAxis, Offset(0f, lane * k), Offset(size.width, lane * k), hairline.toPx(), pathEffect = guide)
            }
            baseline(c.chartAxis, hairline.toPx())
            spans.forEach { s ->
                val from = ((s.startMillis - startMillis) / duration).coerceIn(0f, 1f) * size.width
                val to = ((s.endMillis - startMillis) / duration).coerceIn(0f, 1f) * size.width
                val (ink, laneIndex) = when (s.stage) {
                    SleepStage.Awake -> c.sleepAwake to 0
                    SleepStage.Light -> c.sleepLight to 1
                    SleepStage.Deep -> c.sleepDeep to 2
                }
                val left = if (rtl) size.width - to else from
                val w = max(to - from, 1f)
                val inset = 2.dp.toPx()
                val h = lane - inset * 2
                drawRoundRect(
                    ink, topLeft = Offset(left, lane * laneIndex + inset), size = Size(w, h),
                    cornerRadius = CornerRadius(min(h / 4f, w / 2f)),
                )
            }
        }
        if (startLabel != null || endLabel != null) {
            AxisLabels(listOf(startLabel.orEmpty(), endLabel.orEmpty()), underSlots = false)
        }
    }
}

/** The key to the sleep stages' tints, in the order the band stacks them. */
@Composable
fun SleepLegend(modifier: Modifier = Modifier) {
    val c = NormPlusTheme.colors
    val spacing = NormPlusTheme.spacing
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing.l), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            stringResource(R.string.sleep_deep) to c.sleepDeep,
            stringResource(R.string.sleep_light) to c.sleepLight,
            stringResource(R.string.sleep_awake) to c.sleepAwake,
        ).forEach { (word, ink) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(ink, c.chartAxis)
                Spacer(Modifier.width(spacing.xs))
                Text(word, style = NormPlusTheme.type.chartLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Swatch(ink: Color, edge: Color) {
    val hairline = NormPlusTheme.spacing.hairline
    Box(Modifier.size(10.dp)) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(ink)
            drawCircle(edge, style = Stroke(hairline.toPx()))
        }
    }
}

/** A bar with its top corners rounded (and its bottom ones too for a range). */
private fun DrawScope.drawTopRounded(color: Color, topLeft: Offset, size: Size, radius: Float, roundBottom: Boolean = false) {
    if (radius <= 0f) {
        drawRect(color, topLeft, size)
        return
    }
    val r = min(radius, size.height / 2f)
    val rect = RoundRect(
        left = topLeft.x, top = topLeft.y, right = topLeft.x + size.width, bottom = topLeft.y + size.height,
        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
        bottomLeftCornerRadius = if (roundBottom) CornerRadius(r) else CornerRadius.Zero,
        bottomRightCornerRadius = if (roundBottom) CornerRadius(r) else CornerRadius.Zero,
    )
    drawPath(Path().apply { addRoundRect(rect) }, color)
}

private fun DrawScope.baseline(color: Color, stroke: Float) {
    val y = size.height - stroke / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
}

/**
 * Labels under a chart. [underSlots]: one centred under each slot. Otherwise the first sits
 * at the start, the last at the end, and the rest evenly between, centred. Mirrors in RTL
 * (placeRelative).
 */
@Composable
private fun AxisLabels(labels: List<String>, underSlots: Boolean) {
    val style = NormPlusTheme.type.chartLabel
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = { labels.forEach { Text(it, style = style, color = color, maxLines = 1) } },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = (placeables.maxOfOrNull { it.height } ?: 0) + 4.dp.roundToPx()
        layout(width, height) {
            val n = placeables.size
            placeables.forEachIndexed { i, p ->
                val x = if (underSlots) {
                    val slot = width.toFloat() / n
                    ((i + 0.5f) * slot - p.width / 2f).toInt()
                } else when (i) {
                    0 -> 0
                    n - 1 -> width - p.width
                    else -> (width * i.toFloat() / (n - 1) - p.width / 2f).toInt()
                }
                p.placeRelative(x.coerceIn(0, max(0, width - p.width)), 4.dp.roundToPx())
            }
        }
    }
}
