package com.normplus.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme

/**
 * Where a change to the watch stands. ONE set of marks, the same in settings, in the sync and
 * in the firmware flow (#95): a screen never invents its own.
 */
enum class SendState {
    /** On its way to the watch. */
    Sending,
    /** The watch acknowledged it. */
    Sent,
    /** It did not reach the watch, or the watch refused it. Offer Retry. */
    NotSent,
    /** Queued: it goes when the watch connects, or when its turn comes. */
    Waiting,
}

/**
 * The mark for [state]: a glyph and its word. Each glyph has its own shape (a turning arc, a
 * tick, a clock, an exclamation in a container), so the mark never depends on colour. "Not
 * sent" is an error, so it sits in the error container with its icon, like every error.
 * Changes are announced politely to TalkBack.
 *
 * @param showLabel false only where the row already says it in words (the glyph is then still
 *   described to TalkBack).
 */
@Composable
fun StateMark(state: SendState, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val label = stringResource(
        when (state) {
            SendState.Sending -> R.string.mark_sending
            SendState.Sent -> R.string.mark_sent
            SendState.NotSent -> R.string.mark_not_sent
            SendState.Waiting -> R.string.mark_waiting
        },
    )
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = label
        liveRegion = LiveRegionMode.Polite
    }
    val spacing = NormPlusTheme.spacing
    if (state == SendState.NotSent) {
        Surface(
            modifier = modifier.then(semantics),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            MarkRow(state, label, showLabel, Modifier.padding(PaddingValues(horizontal = spacing.s, vertical = spacing.xxs)))
        }
    } else {
        MarkRow(state, label, showLabel, modifier.then(semantics))
    }
}

@Composable
private fun MarkRow(state: SendState, label: String, showLabel: Boolean, modifier: Modifier) {
    val spacing = NormPlusTheme.spacing
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        MarkGlyph(state, spacing.markIcon)
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = when (state) {
                    SendState.NotSent -> MaterialTheme.colorScheme.onErrorContainer
                    SendState.Sent -> MaterialTheme.colorScheme.onSurface
                    else -> quiet
                },
            )
        }
    }
}

/** The glyph alone, for places that share the marks' vocabulary (the status line's sync). */
@Composable
fun MarkGlyph(state: SendState, size: Dp = NormPlusTheme.spacing.markIcon, tint: Color = Color.Unspecified) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    when (state) {
        SendState.Sending -> SendingGlyph(size, if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.primary)
        SendState.Sent -> Icon(
            Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(size),
            tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.primary,
        )
        SendState.NotSent -> Icon(
            Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(size),
            tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.error,
        )
        SendState.Waiting -> Icon(
            Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(size),
            tint = if (tint != Color.Unspecified) tint else quiet,
        )
    }
}

/**
 * "Sending": an open arc that turns. It is drawn, not Material's indeterminate indicator,
 * because that one shrinks to a dot when animations are removed; this arc stays an arc and
 * simply stops turning.
 */
@Composable
private fun SendingGlyph(size: Dp, tint: Color) {
    val stroke = NormPlusTheme.spacing.hairline * 1.75f
    val angle = if (NormPlusTheme.animationsRemoved) {
        0f
    } else {
        val turning = rememberInfiniteTransition(label = "sending")
        turning.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(TURN_MILLIS, easing = LinearEasing)),
            label = "turn",
        ).value
    }
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val inset = size.toPx() * 0.12f + w / 2f
        rotate(angle) {
            drawArc(
                color = tint,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
    }
}

private const val TURN_MILLIS = 1100
