package com.normplus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.PageColors

/**
 * A day's sheet from the tear-off pad: Today's, or a past day's in Day detail.
 *
 * Square where it is bound at the top, with ONE perforated hairline across it a short stub
 * below the top edge, where the sheet above was torn off. No paper texture, no curl, no
 * shadow stack, no tear animation (the brief's sheet discipline, #95).
 *
 * The sheet always prints in the page's own colours, also when it hangs on a [Plate].
 *
 * @param perforated false for a sheet that is not the top of a pad.
 */
@Composable
fun DaySheet(
    modifier: Modifier = Modifier,
    perforated: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        start = NormPlusTheme.spacing.sheetPadding,
        end = NormPlusTheme.spacing.sheetPadding,
        top = NormPlusTheme.spacing.l,
        bottom = NormPlusTheme.spacing.sheetPadding,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    PageColors {
        Surface(
            modifier = modifier,
            shape = NormPlusTheme.shapes.sheet,
            color = NormPlusTheme.colors.sheet,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column {
                if (perforated) Perforation(Modifier.fillMaxWidth())
                Column(Modifier.padding(contentPadding), content = content)
            }
        }
    }
}

/** The stub and the perforation: a dashed hairline, drawn, never an image. */
@Composable
private fun Perforation(modifier: Modifier = Modifier) {
    val edge = NormPlusTheme.colors.sheetEdge
    val stub = NormPlusTheme.spacing.m
    val hairline = NormPlusTheme.spacing.hairline
    Canvas(modifier.height(stub)) {
        val y = size.height - hairline.toPx() / 2f
        val dash = 3.dp.toPx()
        drawLine(
            color = edge,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = hairline.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
    }
}

/**
 * The edge of the sheet beneath: yesterday's under Today's sheet ("WED 8 · 9,120 steps"),
 * printed red when that day's goal was met. Tapping it opens that day.
 *
 * @param weekday the short weekday, printed in capitals ("Wed").
 * @param day the day of the month ("8").
 * @param summary the day's figure in words ("9,120 steps").
 * @param redLetter the day's goal was met: the day prints red and "Goal reached" is added.
 * @param contentDescription the whole edge as TalkBack reads it, e.g. "Yesterday, Wednesday
 *   8 October, 9,120 steps, goal reached".
 */
@Composable
fun SheetEdge(
    weekday: String,
    day: String,
    summary: String,
    redLetter: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NormPlusTheme.spacing
    PageColors {
        Surface(
            onClick = onClick,
            modifier = modifier
                .heightIn(min = spacing.touchTarget)
                .clearAndSetSemantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
            shape = NormPlusTheme.shapes.sheet,
            color = NormPlusTheme.colors.sheetBelow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = spacing.sheetPadding, vertical = spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (redLetter) NormPlusTheme.colors.redLetter else MaterialTheme.colorScheme.onSurface
                Text(weekday.uppercase(currentLocale()), style = NormPlusTheme.type.dateLine, color = ink)
                Spacer(Modifier.width(spacing.s))
                DateNumeral(day, redLetter = redLetter, style = NormPlusTheme.type.numeralSmall, fitToWidth = false)
                Spacer(Modifier.width(spacing.l))
                Column(Modifier.weight(1f)) {
                    Text(
                        summary,
                        style = NormPlusTheme.type.almanacValue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (redLetter) GoalReachedLabel()
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(spacing.icon),
                )
            }
        }
    }
}
