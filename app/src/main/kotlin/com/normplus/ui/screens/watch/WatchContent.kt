package com.normplus.ui.screens.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.normplus.R
import com.normplus.domain.model.DndSettings
import com.normplus.status.Blocker
import com.normplus.status.Fix
import com.normplus.status.Link
import com.normplus.status.StatusWords
import com.normplus.status.WatchStatus
import com.normplus.ui.components.DialPager
import com.normplus.ui.components.FixItCard
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.SettingRow
import com.normplus.ui.components.SettingsDivider
import com.normplus.ui.components.SettingsSection
import com.normplus.ui.components.WatchScreen
import com.normplus.ui.components.countText
import com.normplus.ui.components.rememberClockFormatter
import com.normplus.ui.components.rememberDialPagerState
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** What Watch's rows and cards do; the route binds them to [WatchViewModel] and the shell. */
class WatchActions(
    val onFix: (Fix) -> Unit = {},
    val onDismissBatteryPrompt: () -> Unit = {},
    val onFindWatch: () -> Unit = {},
    val onBrightness: (Int) -> Unit = {},
    val onTimeout: (Int) -> Unit = {},
    val onDnd: (DndSettings) -> Unit = {},
    val onVibration: (Int) -> Unit = {},
    val onPowerSave: (Boolean) -> Unit = {},
    val onMetric: (Boolean) -> Unit = {},
    val onBit: (SettingKey, Boolean) -> Unit = { _, _ -> },
    val onStepGoal: (Int) -> Unit = {},
    val onDeleteAfterSync: (Boolean) -> Unit = {},
    val onRetry: (SettingKey) -> Unit = {},
    val onDismissSend: (SettingKey) -> Unit = {},
    val onOpenNotificationApps: () -> Unit = {},
    val onOpenWatchScreens: () -> Unit = {},
    val onOpenHandsCalibration: () -> Unit = {},
    val onOpenFirmwareUpdate: () -> Unit = {},
    val onOpenTechnical: () -> Unit = {},
    val onForget: () -> Unit = {},
)

/** The watch's vibration patterns, in VibrationCommand's order (0-14). */
private val VIBRATION_NAMES = listOf(
    R.string.watch_vibration_0, R.string.watch_vibration_1, R.string.watch_vibration_2, R.string.watch_vibration_3,
    R.string.watch_vibration_4, R.string.watch_vibration_5, R.string.watch_vibration_6, R.string.watch_vibration_7,
    R.string.watch_vibration_8, R.string.watch_vibration_9, R.string.watch_vibration_10, R.string.watch_vibration_11,
    R.string.watch_vibration_12, R.string.watch_vibration_13, R.string.watch_vibration_14,
)

private enum class Picker { Timeout, Vibration, Units, StepGoal, DndFrom, DndTo, Forget }

/**
 * Watch (#102), the third tab: the watch drawn live as the hero (the signature), its card, the
 * fix-its, then the settings, each sent as it changes with its own state mark, and Forget this
 * watch at the bottom. It scrolls under the shell's header and capsule ([contentPadding]).
 *
 * @param time where the physical hands stand now (the route passes the live time).
 */
@Composable
fun WatchContent(
    state: WatchUiState,
    status: WatchStatus,
    time: LocalTime,
    date: LocalDate,
    contentPadding: PaddingValues,
    actions: WatchActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    clock: (Long) -> String = rememberClockFormatter(),
) {
    val spacing = NormPlusTheme.spacing
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val live = state.connected
    val asOf = if (!live) state.readAtEpochMs?.let(clock) else null
    val screens = WatchScreen.inWatchOrder(state.settings.pageOrder)
    val pager = rememberDialPagerState(screens)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.gutter,
            end = spacing.gutter,
            top = contentPadding.calculateTopPadding() + spacing.s,
            bottom = contentPadding.calculateBottomPadding() + spacing.l,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.l),
    ) {
        item(key = "dial") {
            DialPager(
                screens = screens,
                time = time,
                date = date,
                state = pager,
                caption = { screen ->
                    Text(
                        stringResource(if (screen == WatchScreen.Face) R.string.watch_dial_face_caption else R.string.watch_dial_page_caption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }
        item(key = "card") { WatchCard(state, status, clock, actions) }
        // Every blocker is a fix-it here, the ones the banner leaves out (or "Not now") included.
        val fixits = status.blockers
        items(fixits.size, key = { "fix-${fixits[it]}" }) { i ->
            val blocker = fixits[i]
            val dismissable = blocker == Blocker.BatteryOptimisationOn && blocker !in status.dismissed
            if (dismissable) {
                Notice(
                    title = stringResource(StatusWords.title(blocker)),
                    tone = NoticeTone.NeedsFixing,
                    body = stringResource(StatusWords.reason(blocker)),
                    actionLabel = stringResource(StatusWords.fixLabel(blocker)),
                    onAction = { actions.onFix(blocker.fix) },
                    secondaryLabel = stringResource(R.string.watch_not_now),
                    onSecondary = actions.onDismissBatteryPrompt,
                )
            } else {
                FixItCard(
                    title = stringResource(StatusWords.title(blocker)),
                    reason = stringResource(StatusWords.reason(blocker)),
                    actionLabel = stringResource(StatusWords.fixLabel(blocker)),
                    onAction = { actions.onFix(blocker.fix) },
                )
            }
        }
        item(key = "notifications") { NotificationsSection(state, live, asOf, actions) }
        item(key = "watch") { WatchSection(state, live, asOf, actions) { picker = it } }
        item(key = "health") { HealthSection(state, live, asOf, actions) { picker = it } }
        item(key = "sync") { SyncSection(state, actions) }
        item(key = "more") { MoreSection(actions) }
        item(key = "forget") {
            PillButton(
                stringResource(R.string.watch_forget),
                onClick = { picker = Picker.Forget },
                tone = PillTone.Quiet,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    when (picker) {
        Picker.Timeout -> ChoiceDialog(
            title = stringResource(R.string.watch_timeout),
            choices = TIMEOUT_CHOICES.map { stringResource(R.string.watch_seconds, it) },
            selected = TIMEOUT_CHOICES.indexOf(state.settings.screenTimeoutSeconds),
            onChoose = { actions.onTimeout(TIMEOUT_CHOICES[it]) },
            onDismiss = { picker = null },
        )
        Picker.Vibration -> ChoiceDialog(
            title = stringResource(R.string.watch_vibration),
            choices = VIBRATION_NAMES.map { stringResource(it) },
            selected = state.settings.vibrationMode,
            onChoose = actions.onVibration,
            onDismiss = { picker = null },
        )
        Picker.Units -> ChoiceDialog(
            title = stringResource(R.string.watch_units),
            choices = listOf(stringResource(R.string.watch_units_metric), stringResource(R.string.watch_units_imperial)),
            selected = if (state.settings.metricUnits) 0 else 1,
            onChoose = { actions.onMetric(it == 0) },
            onDismiss = { picker = null },
        )
        Picker.StepGoal -> ChoiceDialog(
            title = stringResource(R.string.watch_step_goal),
            choices = STEP_GOAL_CHOICES.map { stringResource(R.string.watch_steps, countText(it)) },
            selected = STEP_GOAL_CHOICES.indexOf(state.stepGoal),
            onChoose = { actions.onStepGoal(STEP_GOAL_CHOICES[it]) },
            onDismiss = { picker = null },
        )
        Picker.DndFrom -> TimeDialog(
            title = stringResource(R.string.watch_dnd_from),
            hour = state.dnd.startHour,
            minute = state.dnd.startMin,
            onChoose = { h, m -> actions.onDnd(state.dnd.copy(startHour = h, startMin = m)) },
            onDismiss = { picker = null },
        )
        Picker.DndTo -> TimeDialog(
            title = stringResource(R.string.watch_dnd_to),
            hour = state.dnd.endHour,
            minute = state.dnd.endMin,
            onChoose = { h, m -> actions.onDnd(state.dnd.copy(endHour = h, endMin = m)) },
            onDismiss = { picker = null },
        )
        Picker.Forget -> ForgetDialog(
            onForget = { picker = null; actions.onForget() },
            onBluetoothSettings = { actions.onFix(Fix.OpenBluetoothSettings) },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

// ── The watch card ───────────────────────────────────────────────────────────

@Composable
private fun WatchCard(state: WatchUiState, status: WatchStatus, clock: (Long) -> String, actions: WatchActions) {
    val spacing = NormPlusTheme.spacing
    val link = when (status.link) {
        Link.Ready -> stringResource(R.string.watch_card_connected)
        is Link.Reconnecting, Link.Connecting, Link.Scanning, Link.SettingUp -> stringResource(R.string.watch_card_connecting)
        else -> stringResource(R.string.watch_card_not_connected)
    }
    val lines = buildList {
        add(link)
        status.battery?.let {
            add(stringResource(if (it.charging) R.string.watch_card_battery_charging else R.string.watch_card_battery, it.percent))
        }
        add(
            status.lastSyncEpochMs?.let { stringResource(R.string.watch_card_synced, clock(it)) }
                ?: stringResource(R.string.watch_card_never_synced),
        )
    }
    NormCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(stringResource(R.string.watch_card_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    lines.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton(
                stringResource(R.string.watch_find),
                onClick = actions.onFindWatch,
                tone = PillTone.Tonal,
                enabled = state.connected,
            )
        }
    }
}

// ── The sections ─────────────────────────────────────────────────────────────

@Composable
private fun NotificationsSection(state: WatchUiState, live: Boolean, asOf: String?, actions: WatchActions) {
    SettingsSection(stringResource(R.string.watch_section_notifications)) {
        NavRow(
            stringResource(R.string.watch_notification_apps),
            if (state.forwardingApps == 0) stringResource(R.string.watch_notification_apps_none)
            else pluralString(R.plurals.watch_notification_apps_count, state.forwardingApps),
            actions.onOpenNotificationApps,
        )
        listOf(
            SettingKey.Calls to R.string.watch_kind_calls,
            SettingKey.MissedCalls to R.string.watch_kind_missed_calls,
            SettingKey.Sms to R.string.watch_kind_sms,
            SettingKey.Social to R.string.watch_kind_social,
            SettingKey.Email to R.string.watch_kind_email,
            SettingKey.Calendar to R.string.watch_kind_calendar,
        ).forEach { (key, label) ->
            SettingsDivider()
            SwitchRow(stringResource(label), null, key, state, live, asOf, actions)
        }
    }
}

@Composable
private fun WatchSection(state: WatchUiState, live: Boolean, asOf: String?, actions: WatchActions, open: (Picker) -> Unit) {
    val s = state.settings
    SettingsSection(stringResource(R.string.watch_section_watch)) {
        BrightnessRow(state, live, asOf, actions)
        SettingsDivider()
        PickerRow(stringResource(R.string.watch_timeout), stringResource(R.string.watch_seconds, s.screenTimeoutSeconds), SettingKey.Timeout, state, live, asOf, actions) { open(Picker.Timeout) }
        SettingsDivider()
        SwitchRow(stringResource(R.string.watch_raise_to_wake), stringResource(R.string.watch_raise_to_wake_body), SettingKey.RaiseToWake, state, live, asOf, actions)
        SettingsDivider()
        DndRows(state, live, asOf, actions, open)
        SettingsDivider()
        PickerRow(stringResource(R.string.watch_vibration), stringResource(VIBRATION_NAMES.getOrElse(s.vibrationMode) { R.string.watch_vibration_unknown }), SettingKey.Vibration, state, live, asOf, actions) { open(Picker.Vibration) }
        SettingsDivider()
        NavRow(stringResource(R.string.watch_screens), stringResource(R.string.watch_screens_body), actions.onOpenWatchScreens)
        SettingsDivider()
        SettingRow(
            headline = stringResource(R.string.watch_power_save),
            supporting = stringResource(R.string.watch_power_save_body),
            sendState = state.sends[SettingKey.PowerSave],
            asOf = asOf,
            enabled = live,
            onRetry = { actions.onRetry(SettingKey.PowerSave) },
            onDismiss = { actions.onDismissSend(SettingKey.PowerSave) },
            modifier = Modifier.toggleable(s.powerSaveMode, enabled = live, role = Role.Switch) { actions.onPowerSave(it) },
            trailing = { Switch(s.powerSaveMode, onCheckedChange = null, enabled = live) },
        )
        SettingsDivider()
        PickerRow(
            stringResource(R.string.watch_units),
            stringResource(if (s.metricUnits) R.string.watch_units_metric else R.string.watch_units_imperial),
            SettingKey.Units, state, live, asOf, actions,
        ) { open(Picker.Units) }
    }
}

@Composable
private fun HealthSection(state: WatchUiState, live: Boolean, asOf: String?, actions: WatchActions, open: (Picker) -> Unit) {
    SettingsSection(stringResource(R.string.watch_section_health)) {
        // The goal is Norm+'s own, kept on the phone and sent on every connect: it can change offline.
        SettingRow(
            headline = stringResource(R.string.watch_step_goal),
            supporting = stringResource(R.string.watch_steps, countText(state.stepGoal)),
            sendState = state.sends[SettingKey.StepGoal],
            onRetry = { actions.onRetry(SettingKey.StepGoal) },
            onDismiss = { actions.onDismissSend(SettingKey.StepGoal) },
            modifier = Modifier.clickable(role = Role.Button) { open(Picker.StepGoal) },
            trailing = { Chevron() },
        )
        SettingsDivider()
        SwitchRow(stringResource(R.string.watch_heart_rate_monitor), stringResource(R.string.watch_heart_rate_monitor_body), SettingKey.HeartRateMonitor, state, live, asOf, actions)
        SettingsDivider()
        SwitchRow(stringResource(R.string.watch_sedentary), stringResource(R.string.watch_sedentary_body), SettingKey.Sedentary, state, live, asOf, actions)
        SettingsDivider()
        SwitchRow(stringResource(R.string.watch_goal_alert), stringResource(R.string.watch_goal_alert_body), SettingKey.GoalAlert, state, live, asOf, actions)
    }
}

@Composable
private fun SyncSection(state: WatchUiState, actions: WatchActions) {
    SettingsSection(stringResource(R.string.watch_section_sync)) {
        SettingRow(
            headline = stringResource(R.string.watch_delete_after_sync),
            supporting = stringResource(R.string.watch_delete_after_sync_body),
            modifier = Modifier.toggleable(state.deleteAfterSync, role = Role.Switch) { actions.onDeleteAfterSync(it) },
            trailing = { Switch(state.deleteAfterSync, onCheckedChange = null) },
        )
    }
}

@Composable
private fun MoreSection(actions: WatchActions) {
    SettingsSection(stringResource(R.string.watch_section_more)) {
        NavRow(stringResource(R.string.watch_calibrate), stringResource(R.string.watch_calibrate_body), actions.onOpenHandsCalibration)
        SettingsDivider()
        NavRow(stringResource(R.string.watch_firmware), stringResource(R.string.watch_firmware_body), actions.onOpenFirmwareUpdate)
        SettingsDivider()
        NavRow(stringResource(R.string.watch_technical), stringResource(R.string.watch_technical_body), actions.onOpenTechnical)
    }
}

// ── The rows ─────────────────────────────────────────────────────────────────

@Composable
private fun SwitchRow(
    headline: String,
    supporting: String?,
    key: SettingKey,
    state: WatchUiState,
    live: Boolean,
    asOf: String?,
    actions: WatchActions,
) {
    val on = state.bit(key)
    SettingRow(
        headline = headline,
        supporting = supporting,
        sendState = state.sends[key],
        asOf = asOf,
        enabled = live,
        onRetry = { actions.onRetry(key) },
        onDismiss = { actions.onDismissSend(key) },
        modifier = Modifier.toggleable(on, enabled = live, role = Role.Switch) { actions.onBit(key, it) },
        trailing = { Switch(on, onCheckedChange = null, enabled = live) },
    )
}

@Composable
private fun PickerRow(
    headline: String,
    value: String,
    key: SettingKey,
    state: WatchUiState,
    live: Boolean,
    asOf: String?,
    actions: WatchActions,
    onOpen: () -> Unit,
) {
    SettingRow(
        headline = headline,
        supporting = value,
        sendState = state.sends[key],
        asOf = asOf,
        enabled = live,
        onRetry = { actions.onRetry(key) },
        onDismiss = { actions.onDismissSend(key) },
        modifier = Modifier.clickable(enabled = live, role = Role.Button, onClick = onOpen),
        trailing = { Chevron() },
    )
}

/** A row that opens another screen. */
@Composable
private fun NavRow(headline: String, supporting: String, onClick: () -> Unit) {
    SettingRow(
        headline = headline,
        supporting = supporting,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        trailing = { Chevron() },
    )
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)

/** Brightness: a slider sent when it is let go, not on every step of the drag. */
@Composable
private fun BrightnessRow(state: WatchUiState, live: Boolean, asOf: String?, actions: WatchActions) {
    val spacing = NormPlusTheme.spacing
    var drag by remember(state.settings.brightness) { mutableFloatStateOf(state.settings.brightness.toFloat()) }
    Column {
        SettingRow(
            headline = stringResource(R.string.watch_brightness),
            supporting = stringResource(R.string.watch_percent, drag.roundToInt()),
            sendState = state.sends[SettingKey.Brightness],
            asOf = asOf,
            enabled = live,
            onRetry = { actions.onRetry(SettingKey.Brightness) },
            onDismiss = { actions.onDismissSend(SettingKey.Brightness) },
        )
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { actions.onBrightness(drag.roundToInt()) },
            valueRange = 0f..100f,
            enabled = live,
            modifier = Modifier.padding(horizontal = spacing.gutter).padding(bottom = spacing.s),
        )
    }
}

@Composable
private fun DndRows(state: WatchUiState, live: Boolean, asOf: String?, actions: WatchActions, open: (Picker) -> Unit) {
    val dnd = state.dnd
    SettingRow(
        headline = stringResource(R.string.watch_dnd),
        supporting = stringResource(R.string.watch_dnd_body, hm(dnd.startHour, dnd.startMin), hm(dnd.endHour, dnd.endMin)),
        sendState = state.sends[SettingKey.Dnd],
        asOf = asOf,
        enabled = live,
        onRetry = { actions.onRetry(SettingKey.Dnd) },
        onDismiss = { actions.onDismissSend(SettingKey.Dnd) },
        modifier = Modifier.toggleable(dnd.enabled, enabled = live, role = Role.Switch) { actions.onDnd(dnd.copy(enabled = it)) },
        trailing = { Switch(dnd.enabled, onCheckedChange = null, enabled = live) },
    )
    if (dnd.enabled) {
        SettingsDivider()
        SettingRow(
            headline = stringResource(R.string.watch_dnd_from),
            supporting = hm(dnd.startHour, dnd.startMin),
            enabled = live,
            modifier = Modifier.clickable(enabled = live, role = Role.Button) { open(Picker.DndFrom) },
            trailing = { Chevron() },
        )
        SettingsDivider()
        SettingRow(
            headline = stringResource(R.string.watch_dnd_to),
            supporting = hm(dnd.endHour, dnd.endMin),
            enabled = live,
            modifier = Modifier.clickable(enabled = live, role = Role.Button) { open(Picker.DndTo) },
            trailing = { Chevron() },
        )
    }
}

private fun hm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

@Composable
private fun pluralString(@androidx.annotation.PluralsRes id: Int, count: Int): String =
    androidx.compose.ui.res.pluralStringResource(id, count, countText(count))

// ── The dialogs ──────────────────────────────────────────────────────────────

@Composable
private fun ChoiceDialog(title: String, choices: List<String>, selected: Int, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = i == selected, role = Role.RadioButton) { onChoose(i); onDismiss() }
                            .padding(vertical = NormPlusTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = NormPlusTheme.spacing.m))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, hour: Int, minute: Int, onChoose: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(onClick = { onChoose(picker.hour, picker.minute); onDismiss() }) { Text(stringResource(R.string.watch_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_cancel)) } },
    )
}

/** Forget this watch: Norm+ forgets it; Android's pairing stays, with a way to its settings. */
@Composable
private fun ForgetDialog(onForget: () -> Unit, onBluetoothSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watch_forget_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m)) {
                Text(stringResource(R.string.watch_forget_body))
                TextButton(onClick = onBluetoothSettings) { Text(stringResource(R.string.watch_forget_bluetooth_settings)) }
            }
        },
        confirmButton = { TextButton(onClick = onForget) { Text(stringResource(R.string.watch_forget_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_cancel)) } },
    )
}
