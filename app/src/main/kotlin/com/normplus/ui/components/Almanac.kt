package com.normplus.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import com.normplus.ui.theme.NormPlusTheme
import kotlin.math.max

/**
 * One almanac line under the day's figure: "Sleep  6 h 48 m · deep 1 h 32 m · 23:41–06:29".
 *
 * @param absent the watch has not reported this figure; [value] then holds the words for that
 *   ("No sleep recorded") and prints quieter. Never a zero.
 * @param spokenValue [value] as TalkBack should say it, when it holds a range or symbols. By
 *   default the printer's " · " becomes a pause.
 */
@Immutable
data class AlmanacEntry(
    val label: String,
    val value: String,
    val absent: Boolean = false,
    val spokenValue: String? = null,
)

/**
 * The almanac: small, dense, tabular lines in two columns, labels at the start and values
 * aligned after the widest label (never more than [labelShare] of the width, after which a
 * label wraps). Each line is one TalkBack stop.
 */
@Composable
fun Almanac(
    entries: List<AlmanacEntry>,
    modifier: Modifier = Modifier,
    labelShare: Float = 0.38f,
) {
    val spacing = NormPlusTheme.spacing
    val gap = spacing.l
    val rowGap = spacing.xs
    val labelStyle = NormPlusTheme.type.almanacLabel
    val valueStyle = NormPlusTheme.type.almanacValue
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    Layout(
        modifier = modifier,
        content = {
            entries.forEach { e ->
                val spoken = "${e.label}, ${e.spokenValue ?: e.value.replace(" · ", ", ")}"
                Text(e.label, style = labelStyle, color = labelColor, modifier = Modifier.clearAndSetSemantics { })
                Text(
                    e.value,
                    style = valueStyle,
                    color = if (e.absent) labelColor else valueColor,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val rowGapPx = rowGap.roundToPx()
        val labels = measurables.filterIndexed { i, _ -> i % 2 == 0 }
        val values = measurables.filterIndexed { i, _ -> i % 2 == 1 }
        val labelCap = (width * labelShare).toInt()
        val labelWidth = labels.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) }?.coerceAtMost(labelCap) ?: 0
        val labelPlaceables = labels.map { it.measure(Constraints(maxWidth = labelWidth)) }
        val valueWidth = max(0, width - labelWidth - gapPx)
        val valuePlaceables = values.map { it.measure(Constraints(maxWidth = valueWidth)) }
        val rowHeights = labelPlaceables.zip(valuePlaceables) { l, v -> max(l.height, v.height) }
        val height = rowHeights.sum() + rowGapPx * max(0, rowHeights.size - 1)
        layout(width, height) {
            var y = 0
            rowHeights.forEachIndexed { i, h ->
                labelPlaceables[i].placeRelative(0, y)
                valuePlaceables[i].placeRelative(labelWidth + gapPx, y)
                y += h + rowGapPx
            }
        }
    }
}

/** A single almanac line, for a place that has only one. */
@Composable
fun AlmanacLine(entry: AlmanacEntry, modifier: Modifier = Modifier) = Almanac(listOf(entry), modifier)

