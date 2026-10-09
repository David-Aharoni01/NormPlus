package com.normplus.ui.screens.settings

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.normplus.ui.theme.HrRed
import com.normplus.ui.theme.OnSurfaceMuted
import com.normplus.ui.theme.SuccessGreen
import com.normplus.ui.theme.SurfaceVariant
import com.normplus.ui.theme.Teal

/**
 * "Connection health" card for the settings screen: is the always-on service actually running, is
 * the link up, when did it last succeed — and one-tap fixes for each thing that can silently kill
 * the connection (Bluetooth off, revoked permission, background restriction, Doze).
 *
 * Self-contained (own [ConnectionHealthViewModel]) so it can be dropped into the settings list
 * without entangling it with the watch-settings load/save flow.
 */
@Composable
fun ConnectionHealthSection(viewModel: ConnectionHealthViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Any system dialog we launch can change the facts we sampled — re-check when we come back.
    val systemDialog = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    // Some intents (notably ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) are missing on some OEM
    // builds; try the fallbacks in order rather than crashing on ActivityNotFoundException.
    fun launchFirstAvailable(intents: List<Intent>) {
        for (intent in intents) {
            if (runCatching { systemDialog.launch(intent) }.isSuccess) return
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    SectionHeader("Connection health")
    SettingCard {
        StatusRow(
            label = "Background service",
            value = if (state.serviceRunning) "Running" else "Stopped",
            ok = state.serviceRunning,
        )
        StatusRow(
            label = "Watch link",
            value = state.connectionLabel,
            ok = state.connected,
        )
        StatusRow(
            label = "Last connected",
            value = if (state.lastConnectedEpoch > 0L) {
                DateUtils.getRelativeTimeSpanString(
                    state.lastConnectedEpoch,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            } else "Never",
            ok = state.lastConnectedEpoch > 0L,
        )
        StatusRow(
            label = "Start on boot",
            value = if (state.autoStartEnabled) "Enabled" else "Disabled",
            ok = state.autoStartEnabled,
        )

        // ── Blockers, most severe first ──────────────────────────────────────
        if (!state.hasBlePermission) {
            IssueRow(
                text = "Bluetooth permission is not granted — the watch cannot connect at all.",
                actionLabel = "Open app settings",
                onAction = { launchFirstAvailable(listOf(viewModel.appSettingsIntent())) },
            )
        }
        if (!state.bluetoothOn) {
            IssueRow(
                icon = { Icon(Icons.Default.Bluetooth, null, tint = HrRed, modifier = Modifier.size(18.dp)) },
                text = "Bluetooth is off. The watch reconnects automatically once it's back on.",
                actionLabel = "Bluetooth settings",
                onAction = { launchFirstAvailable(listOf(viewModel.bluetoothSettingsIntent())) },
            )
        }
        if (state.backgroundRestricted) {
            IssueRow(
                text = "This app is restricted in the background, so the system stops the " +
                        "connection service. Set battery usage to \"Unrestricted\".",
                actionLabel = "Open app settings",
                onAction = { launchFirstAvailable(listOf(viewModel.appSettingsIntent())) },
            )
        }
        if (!state.notificationsEnabled) {
            IssueRow(
                text = "Notifications are blocked, so the ongoing connection notification is " +
                        "hidden — the service still runs, but the OS may treat it as less important.",
                actionLabel = "Open app settings",
                onAction = { launchFirstAvailable(listOf(viewModel.appSettingsIntent())) },
            )
        }
        if (state.showBatteryPrompt) {
            IssueRow(
                icon = { Icon(Icons.Default.BatteryAlert, null, tint = Teal, modifier = Modifier.size(18.dp)) },
                text = "Allow background activity so Android doesn't freeze the connection while " +
                        "the phone is idle. This also lets the app restart itself after a kill.",
                actionLabel = "Allow",
                onAction = { launchFirstAvailable(viewModel.batteryOptimizationIntents()) },
                secondaryLabel = "Not now",
                onSecondary = { viewModel.dismissBatteryPrompt() },
            )
        }

        if (!state.serviceRunning) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.startService() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Start connection service", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) SuccessGreen else OnSurfaceMuted,
        )
    }
}

@Composable
private fun IssueRow(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) icon() else
                Icon(Icons.Default.Warning, null, tint = HrRed, modifier = Modifier.size(18.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(
                    onClick = onSecondary,
                    colors = ButtonDefaults.textButtonColors(contentColor = OnSurfaceMuted),
                ) { Text(secondaryLabel, style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(
                onClick = onAction,
                colors = ButtonDefaults.textButtonColors(contentColor = Teal),
            ) { Text(actionLabel, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
