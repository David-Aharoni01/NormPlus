package com.normplus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.normplus.ui.theme.SleepDeepPurple
import com.normplus.ui.theme.SleepLightPurple
import com.normplus.ui.theme.SleepPurple
import com.normplus.ui.theme.SurfaceVariant

// ── Line Chart ─────────────────────────────────────────────────────────────────

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
            drawPath(fillPath, Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.4f), lineColor.copy(alpha = 0f))
            ))
        }

        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ── Bar Chart ──────────────────────────────────────────────────────────────────

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
            val y = size.height - barH
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}

// ── Sleep Chart ────────────────────────────────────────────────────────────────

data class SleepSegment(val startEpoch: Long, val endEpoch: Long, val stage: Int)

@Composable
fun SleepChart(
    segments: List<SleepSegment>,
    modifier: Modifier = Modifier.fillMaxWidth().height(48.dp),
) {
    if (segments.isEmpty()) return
    // SleepStage codes: 0 deep, 1 light, 2 awake (#90).
    val stageColor = mapOf(0 to SleepDeepPurple, 1 to SleepLightPurple, 2 to SleepPurple)

    Canvas(modifier = modifier) {
        val minT = segments.minOf { it.startEpoch }.toFloat()
        val maxT = segments.maxOf { it.endEpoch }.toFloat()
        val range = (maxT - minT).coerceAtLeast(1f)

        drawRoundRect(SurfaceVariant, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))

        segments.forEach { seg ->
            val x = ((seg.startEpoch - minT) / range) * size.width
            val w = ((seg.endEpoch - seg.startEpoch) / range) * size.width
            val color = stageColor[seg.stage] ?: SurfaceVariant
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )
        }
    }
}

// ── GPS Polyline ───────────────────────────────────────────────────────────────

data class LatLon(val lat: Double, val lon: Double)

@Composable
fun GpsPolyline(
    points: List<LatLon>,
    trackColor: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(200.dp),
) {
    if (points.size < 2) return
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
        points.drop(1).forEach { p ->
            val o = toOffset(p)
            path.lineTo(o.x, o.y)
        }
        drawPath(path, color = trackColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        // Start dot
        drawCircle(trackColor, radius = 6.dp.toPx(), center = toOffset(points.first()))
        // End dot
        drawCircle(Color.White, radius = 5.dp.toPx(), center = toOffset(points.last()))
    }
}
