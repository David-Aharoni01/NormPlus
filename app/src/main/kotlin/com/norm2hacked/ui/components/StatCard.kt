package com.norm2hacked.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norm2hacked.ui.theme.Surface
import com.norm2hacked.ui.theme.SurfaceVariant

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
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        if (progress != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                CircularProgressArc(progress = progress, color = accentColor)
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
            )
        }
        Text(text = unit, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF909090))
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = Color(0xFF606060))
        }
    }
}

@Composable
private fun CircularProgressArc(progress: Float, color: Color) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = -90f, sweepAngle = 360f,
            useCenter = false, style = stroke,
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false, style = stroke,
        )
    }
}
