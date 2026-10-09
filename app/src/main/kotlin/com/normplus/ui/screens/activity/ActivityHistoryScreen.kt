package com.normplus.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.normplus.domain.model.SportType
import com.normplus.domain.model.WorkoutSummary
import com.normplus.ui.components.BarChart
import com.normplus.ui.components.LineChart
import com.normplus.ui.theme.Background
import com.normplus.ui.theme.CaloriesOrange
import com.normplus.ui.theme.HrRed
import com.normplus.ui.theme.OnSurfaceMuted
import com.normplus.ui.theme.SleepPurple
import com.normplus.ui.theme.StepsBlue
import com.normplus.ui.theme.Surface
import com.normplus.ui.theme.SurfaceVariant
import com.normplus.ui.theme.Teal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ActivityHistoryScreen(
    viewModel: ActivityHistoryViewModel,
    onWorkoutClick: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val tabs = listOf(
        ActivityTab.STEPS to "Steps",
        ActivityTab.HEART_RATE to "Heart Rate",
        ActivityTab.SLEEP to "Sleep",
        ActivityTab.CALORIES to "Calories",
    )
    val tabColors = mapOf(
        ActivityTab.STEPS to StepsBlue,
        ActivityTab.HEART_RATE to HrRed,
        ActivityTab.SLEEP to SleepPurple,
        ActivityTab.CALORIES to CaloriesOrange,
    )
    val selectedColor = tabColors[state.selectedTab] ?: Teal
    val selectedIndex = tabs.indexOfFirst { it.first == state.selectedTab }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Activity",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(16.dp))
        }

        // Tab row
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = Background,
                contentColor = selectedColor,
                indicator = { tabPositions ->
                    if (selectedIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                            color = selectedColor,
                        )
                    }
                },
                edgePadding = 16.dp,
            ) {
                tabs.forEachIndexed { i, (tab, label) ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                label,
                                color = if (state.selectedTab == tab) tabColors[tab]!! else OnSurfaceMuted,
                            )
                        }
                    )
                }
            }
        }

        // Range chips
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.selectedRange == range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(when(range) { ChartRange.WEEK -> "7D"; ChartRange.MONTH -> "30D"; ChartRange.THREE_MONTHS -> "3M" }) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = selectedColor.copy(alpha = 0.2f),
                            selectedLabelColor = selectedColor,
                            containerColor = SurfaceVariant,
                            labelColor = OnSurfaceMuted,
                        ),
                        border = null,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Chart
        item {
            Box(modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(16.dp)
            ) {
                when (state.selectedTab) {
                    ActivityTab.STEPS, ActivityTab.CALORIES ->
                        BarChart(state.chartPoints.map { SimpleDateFormat("EEE", Locale.getDefault()).format(Date(it.first)) to it.second }, selectedColor)
                    ActivityTab.HEART_RATE ->
                        LineChart(state.chartPoints, HrRed)
                    ActivityTab.SLEEP ->
                        Text("Sleep chart — select a session below", color = OnSurfaceMuted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Workouts header
        item {
            Text(
                "Workouts",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
        }

        items(state.workouts) { workout ->
            WorkoutCard(workout = workout, onClick = { onWorkoutClick(workout.id) })
            Spacer(Modifier.height(8.dp))
        }

        if (state.workouts.isEmpty()) {
            item {
                Text(
                    "No workouts yet — sync your watch on the Dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.DirectionsRun, contentDescription = null, tint = Teal, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(SportType.label(workout.sportType), style = MaterialTheme.typography.titleMedium, color = Color.White)
            val date = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(workout.startEpochMs))
            Text(date, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(workout.durationSeconds), style = MaterialTheme.typography.titleMedium, color = Teal)
            Text("${workout.steps} steps", style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
