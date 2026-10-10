package com.normplus.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.normplus.R
import com.normplus.ui.theme.NormMotion
import com.normplus.ui.theme.NormPlusTheme
import kotlin.math.max

/*
 * The day's numbers: the hero card (Today's steps), the metric tiles under it, the row that
 * opens another day. Uniform cards, one accent: the figures are the colour. No rings, no
 * colour per metric.
 */

/**
 * A rounded card on the ground with its hairline: the container every card in the app uses.
 * Clickable when [onClick] is given (then [clickLabel]-less: the caller's semantics name it).
 */
@Composable
fun NormCard(
    modifier: Modifier = Modifier,
    shape: Shape = NormPlusTheme.shapes.card,
    contentPadding: PaddingValues = PaddingValues(NormPlusTheme.spacing.cardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NormPlusTheme.colors
    val border = BorderStroke(NormPlusTheme.spacing.hairline, c.cardHairline)
    val inner: @Composable () -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = c.card, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = c.card, border = border, content = inner)
    }
}

/**
 * Text that shrinks until it fits the width it is given, never growing past [style]'s size:
 * the hero figure fits 40,000 at font scale 1.3, a tile's figure fits its column. One line.
 * Laid out and drawn by itself (no subcomposition), so it also answers intrinsic
 * measurements, which [MetricGrid] asks for.
 */
@Composable
fun FitText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val measurer = rememberTextMeasurer()
    val ink = if (color != Color.Unspecified) color else LocalContentColor.current
    val last = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        content = {},
        modifier = modifier
            .semantics { this.text = text }
            .drawBehind { last[0]?.let { drawText(it, color = ink) } },
    ) { _, constraints ->
        val natural = measurer.measure(text, style, maxLines = 1, softWrap = false)
        val available = constraints.maxWidth
        val fitted = if (available == Constraints.Infinity || natural.size.width <= available || natural.size.width == 0) {
            natural
        } else {
            val scale = available.toFloat() / natural.size.width * 0.98f
            measurer.measure(
                text,
                style.copy(fontSize = style.fontSize * scale, lineHeight = scaled(style.lineHeight, scale)),
                maxLines = 1,
                softWrap = false,
            )
        }
        last[0] = fitted
        val w = fitted.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = fitted.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(w, h) {}
    }
}

private fun scaled(unit: TextUnit, scale: Float): TextUnit = if (unit.isSpecified) unit * scale else unit

/**
 * A figure with its unit set smaller and quieter beside it: "72 bpm", "1,840 kcal". Pass
 * several pairs for a compound figure: ("6", "h"), ("48", "m").
 */
@Composable
fun figure(vararg parts: Pair<String, String?>): AnnotatedString {
    val unit = NormPlusTheme.type.figureUnit
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        parts.forEachIndexed { i, (number, u) ->
            if (i > 0) append(' ')
            append(number)
            if (u != null) {
                append(' ')
                withStyle(unit.toSpanStyle().copy(color = quiet)) { append(u) }
            }
        }
    }
}

/** A duration as a figure: "6 h 48 m", or "48 m" under an hour, the units small. */
@Composable
fun durationFigure(minutes: Int): AnnotatedString {
    val h = minutes / 60
    val m = minutes % 60
    val hUnit = stringResource(R.string.unit_hours_short)
    val mUnit = stringResource(R.string.unit_minutes_short)
    return if (h > 0) figure(h.toString() to hUnit, m.toString().padStart(2, '0') to mUnit) else figure(m.toString() to mUnit)
}

/** "Goal reached", green, with a tick: the words that go with every goal met. */
@Composable
fun GoalReachedChip(modifier: Modifier = Modifier) {
    val c = NormPlusTheme.colors
    TintedPill(container = c.fineContainer, content = c.fine, modifier = modifier, small = true) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(NormPlusTheme.spacing.markIcon))
        PillText(stringResource(R.string.goal_reached))
    }
}

/**
 * A progress bar with round ends: the share done, violet, all of it green once [goalReached].
 * Follows the layout direction, and fills with [NormMotion.stateChange] (a cut with
 * animations removed). Decorative: the card it is in says the sentence.
 */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, goalReached: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    val track = c.raised
    val ink = if (goalReached) c.fine else scheme.primary
    val thick = NormPlusTheme.spacing.progressBar
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shown by animateFloatAsState(
        fraction.coerceIn(0f, 1f),
        NormMotion.stateChange(NormPlusTheme.animationsRemoved),
        label = "progress",
    )
    Canvas(modifier.height(thick)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = r)
        val done = size.width * shown
        if (done > 0f) {
            val w = max(done, size.height)
            val left = if (rtl) size.width - w else 0f
            drawRoundRect(ink, topLeft = Offset(left, 0f), size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/**
 * The hero card: one metric, large and bold, against its goal. A label with its icon, the
 * figure, a supporting line ("of 8,000 · 1,588 to go"), the progress bar, and at the goal
 * the bar turns green and [GoalReachedChip] says it in words. One TalkBack stop that reads
 * [spoken] ("6,412 of 8,000 steps, goal reached"), carrying the progress for TalkBack too.
 *
 * Put the screen's one glow behind it (`Modifier.heroGlow()`).
 *
 * @param figure null when the watch has not reported it: a dash in the secondary colour,
 *   never a zero; then there is no bar.
 * @param note a quiet line under it all, e.g. "as of 09:12" for last known values.
 */
@Composable
fun HeroMetricCard(
    label: String,
    icon: ImageVector,
    figure: String?,
    supporting: String,
    progress: Float?,
    goalReached: Boolean,
    spoken: String,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        },
        shape = NormPlusTheme.shapes.hero,
        contentPadding = PaddingValues(spacing.xl),
    ) {
        CardTitle(label, icon)
        Row(Modifier.padding(top = spacing.l)) {
            FitText(
                AnnotatedString(figure ?: stringResource(R.string.figure_absent)),
                NormPlusTheme.type.heroFigure,
                color = if (figure == null) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            supporting,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s),
        )
        if (progress != null) {
            ProgressBar(progress, Modifier.fillMaxWidth().padding(top = spacing.l), goalReached = goalReached)
        }
        if (goalReached || note != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                if (goalReached) GoalReachedChip()
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Today's (or a past day's) steps as the hero card: the watch's own count against the goal
 * Norm+ keeps (#102), "of 8,000 · 1,588 to go", "Goal reached" at the goal, and the one
 * TalkBack sentence.
 *
 * @param steps null when the watch has not reported a count: absent, never zero.
 */
@Composable
fun StepsHeroCard(steps: Int?, goal: Int, icon: ImageVector, modifier: Modifier = Modifier, note: String? = null) {
    val reached = steps != null && goal > 0 && steps >= goal
    val stepsText = steps?.let { countText(it) }
    val goalText = countText(goal)
    val supporting = when {
        steps == null || reached -> stringResource(R.string.figure_of_goal, goalText)
        else -> stringResource(R.string.figure_of_goal_to_go, goalText, countText(goal - steps))
    }
    val spoken = when {
        stepsText == null -> stringResource(R.string.figure_spoken_absent, goalText)
        reached -> stringResource(R.string.figure_spoken_goal_reached, stepsText, goalText)
        else -> stringResource(R.string.figure_spoken, stepsText, goalText)
    } + (note?.let { ". $it" } ?: "")
    HeroMetricCard(
        label = stringResource(R.string.metric_steps),
        icon = icon,
        figure = stepsText,
        supporting = supporting,
        progress = if (steps == null || goal <= 0) null else steps.toFloat() / goal,
        goalReached = reached,
        spoken = spoken,
        modifier = modifier,
        note = note,
    )
}

/** A card's head: its icon and its label, quiet. */
@Composable
fun CardTitle(label: String, icon: ImageVector?, modifier: Modifier = Modifier) {
    val spacing = NormPlusTheme.spacing
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
        if (icon != null) Icon(icon, null, Modifier.size(spacing.smallIcon), tint = quiet)
        Text(label, style = NormPlusTheme.type.cardTitle, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A metric tile, for the two-column grid under the hero ([MetricGrid]): its icon and label,
 * the figure ([figure], [durationFigure]) and a detail line ("deep 1 h 32 m · 23:41–06:29").
 * One TalkBack stop reading [spoken].
 *
 * @param value null when the watch has not reported it: [absentText] ("No sleep recorded")
 *   takes the figure's place, quieter. Never a zero.
 */
@Composable
fun MetricTile(
    label: String,
    icon: ImageVector,
    value: AnnotatedString?,
    spoken: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    absentText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            if (onClick != null) role = Role.Button
        },
        shape = NormPlusTheme.shapes.tile,
        contentPadding = PaddingValues(spacing.l),
        onClick = onClick,
    ) {
        CardTitle(label, icon)
        if (value != null) {
            FitText(value, NormPlusTheme.type.tileFigure, Modifier.fillMaxWidth().padding(top = spacing.m), color = scheme.onSurface)
        } else {
            Text(
                absentText ?: stringResource(R.string.figure_absent),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.m),
            )
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
    }
}

/**
 * Lays its children out in [columns] equal columns, each row as tall as its tallest tile, so
 * the tiles of a row line up. Mirrors in a right-to-left layout.
 */
@Composable
fun MetricGrid(modifier: Modifier = Modifier, columns: Int = 2, content: @Composable () -> Unit) {
    val gap = NormPlusTheme.spacing.m
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val cell = (width - gapPx * (columns - 1)) / columns
        val rows = measurables.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.maxIntrinsicHeight(cell) } }
        val placeables = rows.mapIndexed { r, row ->
            row.map { it.measure(Constraints.fixed(cell, heights[r])) }
        }
        val total = heights.sum() + gapPx * max(0, heights.size - 1)
        layout(width, total) {
            var y = 0
            placeables.forEachIndexed { r, row ->
                row.forEachIndexed { i, p -> p.placeRelative(i * (cell + gapPx), y) }
                y += heights[r] + gapPx
            }
        }
    }
}

/**
 * A row that opens a day: Today's "Yesterday" row, a History row. A round date badge
 * (weekday and day of the month), the title and the day's figure, "Goal reached" when it was
 * met, and a chevron. One TalkBack stop, a button, reading [spoken] ("Yesterday, Wednesday
 * 8 October, 9,120 steps, goal reached").
 */
@Composable
fun DayRow(
    weekday: String,
    day: String,
    title: String,
    summary: String,
    goalReached: Boolean,
    spoken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    NormCard(
        modifier = modifier.heightIn(min = spacing.touchTarget).clearAndSetSemantics {
            contentDescription = spoken
            role = Role.Button
        },
        shape = NormPlusTheme.shapes.tile,
        contentPadding = PaddingValues(start = spacing.m, end = spacing.s, top = spacing.m, bottom = spacing.m),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
            Surface(shape = NormPlusTheme.shapes.pill, color = if (goalReached) c.fineContainer else c.raised) {
                Column(
                    Modifier.size(spacing.touchTarget + spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val ink = if (goalReached) c.fine else scheme.onSurface
                    Text(weekday, style = MaterialTheme.typography.labelSmall, color = if (goalReached) c.fine else scheme.onSurfaceVariant, maxLines = 1)
                    Text(day, style = MaterialTheme.typography.titleLarge, color = ink, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (goalReached) GoalReachedChip()
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(spacing.icon),
            )
        }
    }
}
