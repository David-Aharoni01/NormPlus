@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui.screens.settings

import com.normplus.ui.legacy.LegacyInk
import com.normplus.ui.legacy.LegacyOnAccent
import com.normplus.ui.legacy.LegacyMuted
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.normplus.domain.model.AppPage
import com.normplus.protocol.commands.SwitchSettingCommand
import com.normplus.ui.legacy.Background
import com.normplus.ui.legacy.HrRed
import com.normplus.ui.legacy.OnSurfaceMuted
import com.normplus.ui.legacy.Surface
import com.normplus.ui.legacy.SurfaceHigh
import com.normplus.ui.legacy.SurfaceVariant
import com.normplus.ui.legacy.Teal
import kotlin.math.roundToInt

@Composable
fun WatchSettingsScreen(
    viewModel: WatchSettingsViewModel,
    onNotificationRulesClick: () -> Unit,
    onFirmwareClick: () -> Unit,
    onCalibrateHandsClick: () -> Unit,
    healthViewModel: ConnectionHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.loadFromWatch() }
    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) snackbar.showSnackbar("Settings saved")
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = LegacyInk)
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Teal, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Connection health ────────────────────────────────────────────
            // First: if the watch is silently disconnected, nothing else on this screen works.
            item { ConnectionHealthSection(healthViewModel) }

            // ── Display ─────────────────────────────────────────────────────
            item {
                SectionHeader("Display")
                SettingCard {
                    SliderRow(
                        label = "Brightness",
                        value = state.settings.brightness / 100f,
                        onValueChange = { viewModel.update { copy(brightness = (it * 100).roundToInt()) } },
                    )
                    SliderRow(
                        label = "Screen Timeout",
                        value = state.settings.screenTimeoutSeconds / 30f,
                        onValueChange = { viewModel.update { copy(screenTimeoutSeconds = (it * 30).roundToInt().coerceAtLeast(1)) } },
                        valueLabel = "${state.settings.screenTimeoutSeconds}s",
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Do Not Disturb ───────────────────────────────────────────────
            item {
                SectionHeader("Do Not Disturb")
                SettingCard {
                    SwitchRow(
                        label = "Enable DND",
                        checked = state.settings.dnd.enabled,
                        onCheckedChange = { viewModel.update { copy(dnd = dnd.copy(enabled = it)) } },
                    )
                    if (state.settings.dnd.enabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("${state.settings.dnd.startHour}:00 – ${state.settings.dnd.endHour}:00",
                            style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Notifications ─────────────────────────────────────────────────
            item {
                SectionHeader("Notifications")
                SettingCard {
                    val mask = state.settings.switchMask
                    listOf(
                        "Calls" to SwitchSettingCommand.BIT_CALL,
                        "SMS" to SwitchSettingCommand.BIT_SMS,
                        "Social" to SwitchSettingCommand.BIT_SOCIAL,
                        "Email" to SwitchSettingCommand.BIT_EMAIL,
                        "Calendar" to SwitchSettingCommand.BIT_CALENDAR,
                        "Sedentary Reminder" to SwitchSettingCommand.BIT_SEDENTARY,
                        "Raise to Wake" to SwitchSettingCommand.BIT_RAISE_WAKE,
                        "HR Monitor" to SwitchSettingCommand.BIT_HEART_RATE_MONITOR,
                        "Goal Achieved" to SwitchSettingCommand.BIT_GOAL_ACHIEVED,
                    ).forEach { (label, bit) ->
                        SwitchRow(
                            label = label,
                            checked = (mask and bit) != 0,
                            onCheckedChange = { viewModel.toggleNotificationBit(bit, it) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNotificationRulesClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Choose Notification Apps", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Fitness ────────────────────────────────────────────────────────
            item {
                SectionHeader("Fitness")
                SettingCard {
                    SwitchRow(
                        label = "Metric Units",
                        checked = state.settings.metricUnits,
                        onCheckedChange = { viewModel.update { copy(metricUnits = it) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Step Goal: ${"%,d".format(state.settings.stepGoal)}",
                        style = MaterialTheme.typography.bodyMedium, color = LegacyInk)
                    Slider(
                        value = state.settings.stepGoal / 20000f,
                        onValueChange = { viewModel.update { copy(stepGoal = (it * 20000).roundToInt().coerceAtLeast(1000)) } },
                        colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Vibration ──────────────────────────────────────────────────────
            item {
                SectionHeader("Vibration")
                SettingCard {
                    val modes = listOf("Off", "1×Long", "1×Short", "2×Long", "2×Short", "Long+Short",
                        "Cont.Long", "Cont.Short", "5×Long", "Sound", "2×Sound", "Cont.Sound",
                        "Shock+Sound", "Cont.Shock+Sound", "Mute")
                    Text("Pattern: ${modes.getOrElse(state.settings.vibrationMode) { "Unknown" }}",
                        style = MaterialTheme.typography.bodyMedium, color = LegacyInk)
                    Slider(
                        value = state.settings.vibrationMode / 14f,
                        onValueChange = { viewModel.update { copy(vibrationMode = (it * 14).roundToInt()) } },
                        steps = 13,
                        colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Watch Screens ──────────────────────────────────────────────────
            item {
                SectionHeader("Watch Screens")
                SettingCard {
                    Text("Drag to reorder screens shown on the watch:",
                        style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
                    Spacer(Modifier.height(8.dp))
                    state.settings.pageOrder.forEachIndexed { index, pageId ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.DragHandle, contentDescription = null, tint = SurfaceHigh, modifier = Modifier.size(20.dp))
                            Text(
                                "${index + 1}. ${AppPage.label(pageId)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LegacyInk,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("(Drag reordering requires touch gesture — tap up/down arrows to reorder for now)",
                        style = MaterialTheme.typography.labelSmall, color = LegacyMuted)
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Sync ──────────────────────────────────────────────────────────
            // The phone's own setting (#91): it applies at once, not on Save.
            item {
                SectionHeader("Sync")
                SettingCard {
                    SwitchRow(
                        label = "Delete records from the watch after syncing",
                        checked = state.deleteAfterSync,
                        onCheckedChange = { viewModel.setDeleteAfterSync(it) },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.deleteAfterSync)
                            "Each sync reads only what is new. Records stay on the watch until this app has them all."
                        else
                            "Every sync reads every record again. Keeps them on the watch for another app.",
                        style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Power ─────────────────────────────────────────────────────────
            item {
                SectionHeader("Power")
                SettingCard {
                    SwitchRow(
                        label = "Power Save Mode",
                        checked = state.settings.powerSaveMode,
                        onCheckedChange = { viewModel.update { copy(powerSaveMode = it) } },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Device ────────────────────────────────────────────────────────
            item {
                SectionHeader("Device")
                SettingCard {
                    OutlinedButton(
                        onClick = { viewModel.findWatch() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
                    ) { Text("Find Watch (Vibrate)", style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCalibrateHandsClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Calibrate Watch Hands", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onFirmwareClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HrRed),
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Firmware Management", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // Save button
            item {
                Button(
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = LegacyOnAccent),
                    enabled = !state.isSaving,
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = LegacyOnAccent, strokeWidth = 2.dp)
                    } else {
                        Text("Save Changes", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun SettingCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LegacyInk)
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = LegacyOnAccent, checkedTrackColor = Teal),
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValueChange: (Float) -> Unit, valueLabel: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LegacyInk)
        if (valueLabel != null) Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
    Slider(
        value = value, onValueChange = onValueChange,
        colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = SurfaceVariant),
    )
}
