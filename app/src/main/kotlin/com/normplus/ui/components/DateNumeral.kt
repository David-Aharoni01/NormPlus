package com.normplus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalDate

/**
 * A figure set like a calendar's date: the condensed heavy cut, tabular. In its red-letter
 * state it prints in calendar red, as a calendar prints a holiday. Red is never the only
 * signal: whoever shows a red numeral also shows [GoalReachedLabel] and says it to TalkBack
 * ([DayFigure] does both).
 *
 * @param fitToWidth shrink the figure until it fits the width it is given, so 40,000 steps
 *   fits on the sheet at font scale 1.3. It never grows past [style]'s size.
 */
@Composable
fun DateNumeral(
    text: String,
    modifier: Modifier = Modifier,
    redLetter: Boolean = false,
    style: TextStyle = NormPlusTheme.type.numeral,
    color: Color = Color.Unspecified,
    fitToWidth: Boolean = true,
) {
    val ink = when {
        redLetter -> NormPlusTheme.colors.redLetter
        color != Color.Unspecified -> color
        else -> MaterialTheme.colorScheme.onSurface
    }
    if (!fitToWidth) {
        Text(text, modifier = modifier, style = style, color = ink, maxLines = 1, softWrap = false)
        return
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val fitted = remember(text, style, available) {
            val natural = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
            if (natural <= available || natural == 0) style
            else {
                val scale = available.toFloat() / natural * 0.98f
                style.copy(fontSize = style.fontSize * scale, lineHeight = scaled(style.lineHeight, scale))
            }
        }
        Text(text, style = fitted, color = ink, maxLines = 1, softWrap = false)
    }
}

private fun scaled(unit: TextUnit, scale: Float): TextUnit = if (unit.isSpecified) unit * scale else unit

/**
 * The date line at the head of a sheet, in tracked capitals: "THURSDAY · 9 OCTOBER".
 * TalkBack hears "Thursday 9 October".
 */
@Composable
fun DateLine(date: LocalDate, modifier: Modifier = Modifier) {
    val printed = dateLineText(date).uppercase(currentLocale())
    val spoken = dateSpokenText(date)
    Text(
        printed,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        style = NormPlusTheme.type.dateLine,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The words that go with every red-letter numeral: "GOAL REACHED", in calendar red. */
@Composable
fun GoalReachedLabel(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.goal_reached).uppercase(currentLocale()),
        modifier = modifier,
        style = NormPlusTheme.type.dateLine,
        color = NormPlusTheme.colors.redLetter,
    )
}

/**
 * The head of a day sheet: the day's steps set as the date numeral, "of 8,000 steps", one
 * thin progress line, and, once the goal is met, the numeral in calendar red with
 * "GOAL REACHED". TalkBack reads it as one sentence: "6,412 of 8,000 steps, goal reached".
 *
 * @param steps the watch's own count; null when it has not reported one, which prints a dash
 *   and is never shown as zero.
 * @param goal the goal Norm+ keeps (#102).
 */
@Composable
fun DayFigure(steps: Int?, goal: Int, modifier: Modifier = Modifier) {
    val reached = steps != null && goal > 0 && steps >= goal
    val stepsText = steps?.let { countText(it) }
    val goalText = countText(goal)
    val spoken = when {
        stepsText == null -> stringResource(R.string.figure_spoken_absent, goalText)
        reached -> stringResource(R.string.figure_spoken_goal_reached, stepsText, goalText)
        else -> stringResource(R.string.figure_spoken, stepsText, goalText)
    }
    val fraction = if (steps == null || goal <= 0) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
    val spacing = NormPlusTheme.spacing
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = spoken
            if (steps != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        },
    ) {
        DateNumeral(
            text = stepsText ?: stringResource(R.string.figure_absent),
            redLetter = reached,
            color = if (steps == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.s))
        Text(
            stringResource(R.string.figure_of_goal_steps, goalText),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.m))
        ProgressLine(fraction = fraction, redLetter = reached, modifier = Modifier.fillMaxWidth())
        if (reached) {
            Spacer(Modifier.height(spacing.s))
            GoalReachedLabel()
        }
    }
}

/**
 * One thin line from the start: the share of the goal done. A hairline track, the done part
 * in ink, all of it red once the goal is met. Follows the layout direction.
 */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, redLetter: Boolean = false) {
    val track = NormPlusTheme.colors.sheetEdge
    val ink = if (redLetter) NormPlusTheme.colors.redLetter else MaterialTheme.colorScheme.onSurface
    val thick = NormPlusTheme.spacing.progressLine
    val hairline = NormPlusTheme.spacing.hairline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.height(thick)) {
        val y = size.height / 2f
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = hairline.toPx())
        val done = size.width * fraction.coerceIn(0f, 1f)
        if (done > 0f) {
            val (from, to) = if (rtl) size.width to size.width - done else 0f to done
            drawLine(ink, Offset(from, y), Offset(to, y), strokeWidth = thick.toPx(), cap = StrokeCap.Butt)
        }
    }
}
