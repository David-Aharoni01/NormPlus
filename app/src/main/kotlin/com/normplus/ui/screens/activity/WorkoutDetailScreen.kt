package com.normplus.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.normplus.domain.model.SportType
import com.normplus.ui.components.GpsPolyline
import com.normplus.ui.components.LatLon
import com.normplus.ui.components.LineChart
import com.normplus.ui.theme.Background
import com.normplus.ui.theme.CaloriesOrange
import com.normplus.ui.theme.DistanceGreen
import com.normplus.ui.theme.HrRed
import com.normplus.ui.theme.OnSurfaceMuted
import com.normplus.ui.theme.StepsBlue
import com.normplus.ui.theme.Surface
import com.normplus.ui.theme.SurfaceVariant
import com.normplus.ui.theme.Teal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WorkoutDetailScreen(
    viewModel: WorkoutDetailViewModel,
    workoutId: Long,
    onBack: () -> Unit,
) {
    LaunchedEffect(workoutId) { viewModel.load(workoutId) }
    val state by viewModel.state.collectAsState()

    val workout = state.workout ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Back + header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Workout", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        // Hero
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = Teal, modifier = Modifier.size(32.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(SportType.label(workout.sportType), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(
                            SimpleDateFormat("MMMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(workout.startEpochMs)),
                            style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Metrics chips
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricChip("Duration", formatDuration(workout.durationSeconds), Teal)
                    MetricChip("Steps", "%,d".format(workout.steps), StepsBlue)
                    MetricChip("Calories", "${workout.calories.roundToInt()} kcal", CaloriesOrange)
                    MetricChip("Avg HR", "${workout.avgHeartRate} bpm", HrRed)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // HR chart
        if (state.hrPoints.isNotEmpty()) {
            SectionCard(title = "Heart Rate") {
                LineChart(state.hrPoints, HrRed, modifier = Modifier.fillMaxWidth().height(140.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        // GPS polyline
        if (state.gpsPoints.isNotEmpty()) {
            SectionCard(title = "Route") {
                GpsPolyline(
                    state.gpsPoints.map { LatLon(it.lat, it.lon) },
                    trackColor = Teal,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Distance: ${"%.2f".format(workout.distanceMeters / 1000)} km",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistanceGreen,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MetricChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurfaceMuted)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
