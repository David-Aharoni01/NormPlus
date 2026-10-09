package com.normplus.ui.legacy

/*
 * DEPRECATED: everything the pre-redesign screens still need, in one place, so the app
 * builds and runs while the screens are rebuilt one issue at a time (#97-#107, under #92).
 *
 * - The old colour names now resolve to the Day Sheet theme's roles, so the old screens
 *   render in the new palette, in light and in dark, instead of near-black and teal.
 *   LegacyInk, LegacyOnAccent and LegacyMuted stand in for the Color.White, Color.Black and
 *   greys the old screens wrote inline.
 * - The old StatCard and charts are kept as they were, coloured through the same names.
 *
 * A rebuilt screen imports nothing from this package. Delete this file, and the package,
 * when the last old screen is gone; the compiler then names anything still using it.
 * New code uses MaterialTheme, NormPlusTheme and com.normplus.ui.components.
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.normplus.ui.theme.NormPlusTheme

private const val OLD = "Pre-redesign (#96): use MaterialTheme.colorScheme, NormPlusTheme and ui.components"

// ── The old colour names, mapped onto the new roles ───────────────────────────

@Deprecated(OLD) val Background: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
@Deprecated(OLD) val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
@Deprecated(OLD) val SurfaceVariant: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh
@Deprecated(OLD) val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
@Deprecated(OLD) val OnSurface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
@Deprecated(OLD) val OnSurfaceMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** The old accent. The new world's accent for controls is the ink-blue. */
@Deprecated(OLD) val Teal: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
@Deprecated(OLD) val TealDark: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer

// A colour per metric is gone in the new world: every metric prints in ink.
@Deprecated(OLD) val StepsBlue: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
/** Was the sleep accent (a label colour on the old dashboard); the stages use NormPlusTheme.colors. */
@Deprecated(OLD) val SleepPurple: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
@Deprecated(OLD) val SleepDeepPurple: Color @Composable @ReadOnlyComposable get() = NormPlusTheme.colors.sleepDeep
@Deprecated(OLD) val SleepLightPurple: Color @Composable @ReadOnlyComposable get() = NormPlusTheme.colors.sleepLight
@Deprecated(OLD) val CaloriesOrange: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
@Deprecated(OLD) val DistanceGreen: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
@Deprecated(OLD) val ActiveTimeAmber: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

/** The old screens used it for warnings and destructive actions: that is the error role now. */
@Deprecated(OLD) val HrRed: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
@Deprecated(OLD) val SuccessGreen: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
@Deprecated(OLD) val ErrorRed: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

/** Was Color.White: text and icons. */
@Deprecated(OLD) val LegacyInk: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
/** Was Color.Black on the teal accent: content on a filled control. */
@Deprecated(OLD) val LegacyOnAccent: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
/** Was Color(0xFF606060) and the other inline greys. */
@Deprecated(OLD) val LegacyMuted: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

// ── The old StatCard ───────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
@Deprecated(OLD)
@Composable
fun StatCard(
    label: String,
    value: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,  // 0f..1f for circular ring
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = accentColor)
        Spacer(Modifier.height(8.dp))
        if (progress != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                CircularProgressArc(progress = progress, color = accentColor)
                Text(text = value, style = MaterialTheme.typography.headlineSmall, color = LegacyInk)
            }
        } else {
            Text(text = value, style = MaterialTheme.typography.headlineLarge, color = LegacyInk)
        }
        Text(text = unit, style = MaterialTheme.typography.bodyMedium, color = LegacyMuted)
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = LegacyMuted)
        }
    }
}

@Composable
private fun CircularProgressArc(progress: Float, color: Color) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color = color.copy(alpha = 0.2f), startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
        drawArc(
            color = color, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false, style = stroke,
        )
    }
}

// ── The old charts ─────────────────────────────────────────────────────────────

@Deprecated(OLD)
@Composable
fun LineChart(
    points: List<Pair<Long, Float>>,  // (epochMs, value)
    lineColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    showFill: Boolean = true,
) {
    if (points.size < 2) return
    Canvas(modifier = modifier) {
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }.coerceAtLeast(minY + 1f)
        val minX = points.minOf { it.first }.toFloat()
        val maxX = points.maxOf { it.first }.toFloat()

        fun xPos(epoch: Long) = ((epoch - minX) / (maxX - minX)) * size.width
        fun yPos(v: Float) = size.height - ((v - minY) / (maxY - minY)) * size.height * 0.9f

        val path = Path()
        path.moveTo(xPos(points.first().first), yPos(points.first().second))
        points.drop(1).forEach { (t, v) -> path.lineTo(xPos(t), yPos(v)) }

        if (showFill) {
            val fillPath = Path().also {
                it.addPath(path)
                it.lineTo(xPos(points.last().first), size.height)
                it.lineTo(xPos(points.first().first), size.height)
                it.close()
            }
            drawPath(fillPath, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.4f), lineColor.copy(alpha = 0f))))
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Deprecated(OLD)
@Composable
fun BarChart(
    bars: List<Pair<String, Float>>,  // (label, value)
    barColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
) {
    if (bars.isEmpty()) return
    Canvas(modifier = modifier) {
        val maxVal = bars.maxOf { it.second }.coerceAtLeast(1f)
        val barWidth = (size.width / bars.size) * 0.6f
        val gap = (size.width / bars.size) * 0.4f
        bars.forEachIndexed { i, (_, value) ->
            val x = i * (barWidth + gap) + gap / 2f
            val barH = (value / maxVal) * size.height * 0.9f
            drawRoundRect(
                color = barColor, topLeft = Offset(x, size.height - barH), size = Size(barWidth, barH),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

@Deprecated(OLD)
data class SleepSegment(val startEpoch: Long, val endEpoch: Long, val stage: Int)

@Suppress("DEPRECATION", "ModifierParameter") // kept as it was
@Deprecated(OLD)
@Composable
fun SleepChart(
    segments: List<SleepSegment>,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
) {
    if (segments.isEmpty()) return
    // SleepStage codes: 0 deep, 1 light, 2 awake (#90).
    val stageColor = mapOf(0 to SleepDeepPurple, 1 to SleepLightPurple, 2 to NormPlusTheme.colors.sleepAwake)
    val track = SurfaceVariant
    Canvas(modifier = modifier) {
        val minT = segments.minOf { it.startEpoch }.toFloat()
        val maxT = segments.maxOf { it.endEpoch }.toFloat()
        val range = (maxT - minT).coerceAtLeast(1f)
        drawRoundRect(track, cornerRadius = CornerRadius(8.dp.toPx()))
        segments.forEach { seg ->
            val x = ((seg.startEpoch - minT) / range) * size.width
            val w = ((seg.endEpoch - seg.startEpoch) / range) * size.width
            drawRoundRect(
                color = stageColor[seg.stage] ?: track, topLeft = Offset(x, 0f), size = Size(w, size.height),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

@Deprecated(OLD)
data class LatLon(val lat: Double, val lon: Double)

@Suppress("DEPRECATION")
@Deprecated(OLD)
@Composable
fun GpsPolyline(
    points: List<LatLon>,
    trackColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(200.dp),
) {
    if (points.size < 2) return
    val endDot = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier) {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }.coerceAtLeast(minLat + 0.0001)
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }.coerceAtLeast(minLon + 0.0001)

        fun toOffset(p: LatLon): Offset {
            val x = ((p.lon - minLon) / (maxLon - minLon) * size.width).toFloat()
            val y = (size.height - (p.lat - minLat) / (maxLat - minLat) * size.height).toFloat()
            return Offset(x, y)
        }

        val path = Path()
        path.moveTo(toOffset(points.first()).x, toOffset(points.first()).y)
        points.drop(1).forEach { p -> toOffset(p).let { path.lineTo(it.x, it.y) } }
        drawPath(path, color = trackColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(trackColor, radius = 6.dp.toPx(), center = toOffset(points.first()))
        drawCircle(endDot, radius = 5.dp.toPx(), center = toOffset(points.last()))
    }
}
