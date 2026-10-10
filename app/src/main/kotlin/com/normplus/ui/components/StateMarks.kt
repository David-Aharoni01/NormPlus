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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
 * The mark for [state]: a small tinted pill with a glyph and its word, the same anatomy as
 * the status pill. Sending is violet with a turning arc, sent green with a tick, not sent red
 * with an exclamation, waiting grey with a clock: each glyph has its own shape, so the mark
 * never depends on colour. Changes are announced politely to TalkBack.
 *
 * @param showLabel false only where the row already says it in words (the glyph is then still
 *   described to TalkBack, and the pill shrinks to the glyph).
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
    val (container, content) = markColors(state)
    TintedPill(
        container = container,
        content = content,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        small = true,
    ) {
        MarkGlyph(state, NormPlusTheme.spacing.markIcon, tint = content)
        if (showLabel) PillText(label, NormPlusTheme.type.pill.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize))
    }
}

@Composable
private fun markColors(state: SendState): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    return when (state) {
        SendState.Sending -> scheme.primaryContainer to (if (NormPlusTheme.isDark) scheme.primary else scheme.onPrimaryContainer)
        SendState.Sent -> c.fineContainer to c.fine
        SendState.NotSent -> scheme.errorContainer to scheme.onErrorContainer
        SendState.Waiting -> c.raised to scheme.onSurfaceVariant
    }
}

/** The glyph alone, for places that share the marks' vocabulary (the status pill's sync). */
@Composable
fun MarkGlyph(state: SendState, size: Dp = NormPlusTheme.spacing.markIcon, tint: Color = Color.Unspecified) {
    val scheme = MaterialTheme.colorScheme
    fun ink(default: Color) = if (tint != Color.Unspecified) tint else default
    when (state) {
        SendState.Sending -> SendingGlyph(size, ink(scheme.primary))
        SendState.Sent -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(size), tint = ink(NormPlusTheme.colors.fine))
        SendState.NotSent -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(size), tint = ink(scheme.error))
        SendState.Waiting -> Icon(Icons.Rounded.Schedule, null, Modifier.size(size), tint = ink(scheme.onSurfaceVariant))
    }
}

/**
 * "Sending": an open arc that turns. It is drawn, not Material's indeterminate indicator,
 * because that one shrinks to a dot when animations are removed; this arc stays an arc and
 * simply stops turning.
 */
@Composable
internal fun SendingGlyph(size: Dp, tint: Color) {
    val stroke = NormPlusTheme.spacing.hairline * 2f
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

/**
 * The tinted pill every small state shares: the status pill, the state marks, "Goal
 * reached". A glyph and words on the state's container, in the state's colour.
 */
@Composable
internal fun TintedPill(
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    body: @Composable RowScope.() -> Unit,
) {
    val spacing = NormPlusTheme.spacing
    Surface(modifier = modifier, shape = NormPlusTheme.shapes.pill, color = container, contentColor = content) {
        Row(
            Modifier
                .heightIn(min = if (small) spacing.l + spacing.s else spacing.xxl)
                .padding(
                    PaddingValues(
                        start = if (small) spacing.s else spacing.m,
                        end = if (small) spacing.s + spacing.xxs else spacing.l,
                        top = spacing.xxs,
                        bottom = spacing.xxs,
                    ),
                ),
            horizontalArrangement = Arrangement.spacedBy(if (small) spacing.xs + spacing.xxs else spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            content = body,
        )
    }
}

@Composable
internal fun PillText(text: String, style: TextStyle = NormPlusTheme.type.pill) {
    Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
