@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui.screens.dashboard

import com.normplus.ui.legacy.LegacyInk
import com.normplus.ui.legacy.LegacyOnAccent
import com.normplus.ui.legacy.LegacyMuted
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.normplus.ble.BleConnectionState
import com.normplus.ui.legacy.StatCard
import com.normplus.ui.legacy.Background
import com.normplus.ui.legacy.CaloriesOrange
import com.normplus.ui.legacy.HrRed
import com.normplus.ui.legacy.OnSurfaceMuted
import com.normplus.ui.legacy.SleepPurple
import com.normplus.ui.legacy.StepsBlue
import com.normplus.ui.legacy.Teal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.sync() },
                containerColor = Teal,
                contentColor = LegacyOnAccent,
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = LegacyOnAccent, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Sync, "Sync")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { viewModel.sync() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullRefreshState,
                    isRefreshing = state.isSyncing,
                    containerColor = Teal,
                    color = LegacyOnAccent,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(16.dp))

                // Top bar
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Norm 2", style = MaterialTheme.typography.headlineMedium, color = LegacyInk)
                        val connLabel = when (val cs = state.connectionState) {
                            is BleConnectionState.Ready -> cs.deviceName
                            is BleConnectionState.Scanning -> "Scanning…"
                            is BleConnectionState.Connecting -> "Connecting…"
                            is BleConnectionState.Discovering -> "Setting up…"
                            is BleConnectionState.Error -> "Reconnecting…"
                            else -> "Disconnected"
                        }
                        val connColor = if (state.connectionState is BleConnectionState.Ready) Teal else OnSurfaceMuted
                        Text("● $connLabel", style = MaterialTheme.typography.bodyMedium, color = connColor)
                    }
                    // Battery
                    if (state.batteryPercent > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (state.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                contentDescription = null,
                                tint = if (state.batteryPercent < 20) HrRed else Teal,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "${state.batteryPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.batteryPercent < 20) HrRed else LegacyInk,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("Today", style = MaterialTheme.typography.titleLarge, color = OnSurfaceMuted)
                Spacer(Modifier.height(12.dp))

                // 2×2 stat grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        label = "Steps",
                        value = "%,d".format(state.steps),
                        unit = "/ ${"%,d".format(state.stepGoal)}",
                        accentColor = StepsBlue,
                        progress = (state.steps.toFloat() / state.stepGoal).coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Heart Rate",
                        value = if (state.lastHrBpm > 0) "${state.lastHrBpm}" else "--",
                        unit = "bpm",
                        subtitle = if (state.lastHrTimestamp > 0) relativeTime(state.lastHrTimestamp) else null,
                        accentColor = HrRed,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sleepHours = state.sleepMinutes / 60
                    val sleepMins = state.sleepMinutes % 60
                    StatCard(
                        label = "Sleep",
                        value = "${sleepHours}h ${sleepMins}m",
                        unit = "${state.deepSleepMinutes}m deep",
                        accentColor = SleepPurple,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Calories",
                        value = "${state.calories.roundToInt()}",
                        unit = "kcal",
                        accentColor = CaloriesOrange,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (state.syncLabel.isNotEmpty()) {
                    Text(
                        state.syncLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Teal,
                    )
                } else if (state.lastSyncEpoch > 0) {
                    Text(
                        "Last synced ${relativeTime(state.lastSyncEpoch)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }

                if (state.deviceVersion.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "v${state.deviceVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LegacyMuted,
                    )
                }

                Spacer(Modifier.height(80.dp))  // FAB clearance
            }

        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / 60_000}m ago"
        diff < TimeUnit.HOURS.toMillis(24) -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}
