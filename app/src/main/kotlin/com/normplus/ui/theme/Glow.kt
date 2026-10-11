package com.normplus.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * The one soft violet glow behind a screen's hero (the dial on Watch, the steps card on
 * Today, the dial in calibration). ONE per screen, never behind anything else.
 *
 * It is a single radial gradient drawn behind the element, reaching [spread] times the
 * element's half-size past its centre, so it spills softly onto the ground round it. One
 * draw call, cached until the size changes: cheap enough for a scrolling list. Its strength
 * comes from the theme ([NormColors.glow], [NormColors.glowAlpha]), so it is a haze on the
 * light ground and a glow on the dark one. It is not animated.
 *
 * @param centerY where the glow's centre sits, as a fraction of the element's height
 *   (0.5, the middle, by default; lower it to sit the glow behind a card's figure).
 */
fun Modifier.heroGlow(spread: Float = 1.5f, centerY: Float = 0.5f): Modifier = composed {
    val colors = NormPlusTheme.colors
    glowBehind(colors.glow, colors.glowAlpha, spread, centerY)
}

private fun Modifier.glowBehind(glow: Color, alpha: Float, spread: Float, centerY: Float): Modifier =
    drawWithCache {
        val radius = max(size.width, size.height) / 2f * spread
        val center = Offset(size.width / 2f, size.height * centerY)
        val brush = Brush.radialGradient(
            0f to glow.copy(alpha = alpha),
            0.45f to glow.copy(alpha = alpha * 0.45f),
            1f to glow.copy(alpha = 0f),
            center = center,
            radius = radius.coerceAtLeast(1f),
        )
        onDrawBehind { drawCircle(brush, radius = radius, center = center) }
    }
