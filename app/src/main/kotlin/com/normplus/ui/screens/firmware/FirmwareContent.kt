package com.normplus.ui.screens.firmware

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.normplus.R
import com.normplus.protocol.ota.OtaStep
import com.normplus.ui.components.FlowScaffold
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.ProgressBar
import com.normplus.ui.components.SendState
import com.normplus.ui.components.StateMark
import com.normplus.ui.components.countText
import com.normplus.ui.theme.NormPlusTheme

/** What the firmware flow's controls do; the route binds them to [FirmwareViewModel]. */
class FirmwareActions(
    val onClose: () -> Unit = {},
    val onChoose: (FirmwareTarget?) -> Unit = {},
    val onAcknowledge: (Boolean) -> Unit = {},
    val onStart: () -> Unit = {},
    val onSendAgain: () -> Unit = {},
)

/** Where the flow is (#106), from [FirmwareUiState] alone. */
enum class FirmwarePhase { Choose, Review, Running, Done, Failed }

val FirmwareUiState.phase: FirmwarePhase
    get() = when {
        progress?.isDone == true -> FirmwarePhase.Done
        progress?.isFailed == true -> FirmwarePhase.Failed
        isFlashing -> FirmwarePhase.Running
        target != null -> FirmwarePhase.Review
        else -> FirmwarePhase.Choose
    }

/**
 * The firmware update (#106), a full-screen flow: what the watch runs now and what can be sent;
 * a review that takes two deliberate acts (tick, then Start), held back while the watch has
 * just connected; then progress, full screen, saying the watch is blank on purpose; then done,
 * or where it stopped with Send again, the raw step and error behind Details.
 *
 * Only the original resources (type 4) or a custom file chosen on Technical can be sent: Norm+'s
 * own main firmware is shown and cannot be started until #93. The update itself is
 * [FirmwareViewModel]'s, unchanged: ApolloOtaSession, which refuses a main-firmware image.
 */
@Composable
fun FirmwareContent(state: FirmwareUiState, actions: FirmwareActions) {
    var confirmLeave by remember { mutableStateOf(false) }
    val phase = state.phase
    val close = { if (phase == FirmwarePhase.Running) confirmLeave = true else actions.onClose() }
    // System Back while the update is sending asks first, as Close does: leaving stops it.
    BackHandler(enabled = phase == FirmwarePhase.Running) { confirmLeave = true }
    // The screen stays on while the update runs.
    val view = LocalView.current
    DisposableEffect(phase == FirmwarePhase.Running) {
        view.keepScreenOn = phase == FirmwarePhase.Running
        onDispose { view.keepScreenOn = false }
    }
    FlowScaffold(
        title = stringResource(R.string.firmware_title),
        onClose = close,
        step = when (phase) {
            FirmwarePhase.Choose -> stringResource(R.string.firmware_step_choose)
            FirmwarePhase.Review -> stringResource(R.string.firmware_step_review)
            FirmwarePhase.Running -> stringResource(R.string.firmware_step_running)
            else -> null
        },
        actions = { BottomActions(state, phase, actions, close) },
    ) {
        when (phase) {
            FirmwarePhase.Choose -> Choose(state, actions)
            FirmwarePhase.Review -> Review(state, actions)
            FirmwarePhase.Running -> Running(state)
            FirmwarePhase.Done -> Done()
            FirmwarePhase.Failed -> Failed(state)
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.firmware_leave_title)) },
            text = { Text(stringResource(R.string.firmware_leave_body)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; actions.onClose() }) { Text(stringResource(R.string.firmware_leave_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.firmware_leave_stay)) } },
        )
    }
}

@Composable
private fun BottomActions(state: FirmwareUiState, phase: FirmwarePhase, actions: FirmwareActions, close: () -> Unit) {
    when (phase) {
        FirmwarePhase.Choose -> Unit
        FirmwarePhase.Review -> {
            PillButton(stringResource(R.string.firmware_back), { actions.onChoose(null) }, tone = PillTone.Neutral)
            PillButton(stringResource(R.string.firmware_start), actions.onStart, tone = PillTone.Primary, enabled = state.canStart)
        }
        FirmwarePhase.Running -> Unit
        FirmwarePhase.Done -> PillButton(stringResource(R.string.firmware_done), close, tone = PillTone.Primary)
        FirmwarePhase.Failed -> {
            PillButton(stringResource(R.string.firmware_close), close, tone = PillTone.Neutral)
            PillButton(stringResource(R.string.firmware_send_again), actions.onSendAgain, tone = PillTone.Primary, enabled = state.connected)
        }
    }
}

@Composable
private fun Intro(headline: String, body: String) {
    Column(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s),
    ) {
        Text(headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Choose ───────────────────────────────────────────────────────────────────

@Composable
private fun Choose(state: FirmwareUiState, actions: FirmwareActions) {
    val spacing = NormPlusTheme.spacing
    Intro(stringResource(R.string.firmware_choose_headline), stringResource(R.string.firmware_choose_body))
    NormCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.firmware_now_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (state.watchVersion.isNotEmpty()) stringResource(R.string.firmware_now_value, state.watchVersion)
            else stringResource(R.string.firmware_now_unknown),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = spacing.xxs),
        )
    }
    if (state.customFileUri != null) {
        Option(
            title = stringResource(R.string.firmware_custom_title, state.customFileName.orEmpty()),
            body = stringResource(R.string.firmware_custom_body),
            enabled = true,
        ) { actions.onChoose(FirmwareTarget.CustomFile) }
    }
    Option(
        title = stringResource(R.string.firmware_original_title),
        body = stringResource(if (state.bundledAvailable) R.string.firmware_original_body else R.string.firmware_original_missing),
        enabled = state.bundledAvailable,
    ) { actions.onChoose(FirmwareTarget.OriginalResources) }
    // Norm+'s own main firmware: shown, never startable until #93 lands.
    Option(
        title = stringResource(R.string.firmware_normplus_title),
        body = stringResource(R.string.firmware_normplus_body),
        enabled = false,
        locked = true,
    ) {}
}

@Composable
private fun Option(title: String, body: String, enabled: Boolean, locked: Boolean = false, onClick: () -> Unit) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(Modifier.fillMaxWidth(), onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (locked) Icon(Icons.Rounded.Lock, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

// ── Review ───────────────────────────────────────────────────────────────────

@Composable
private fun Review(state: FirmwareUiState, actions: FirmwareActions) {
    val spacing = NormPlusTheme.spacing
    val what = when (state.target) {
        FirmwareTarget.CustomFile -> stringResource(R.string.firmware_custom_title, state.customFileName.orEmpty())
        else -> stringResource(R.string.firmware_original_title)
    }
    Intro(stringResource(R.string.firmware_review_headline, what), stringResource(R.string.firmware_review_body))
    Notice(
        title = stringResource(R.string.firmware_review_warning_title),
        tone = NoticeTone.NeedsFixing,
        body = stringResource(R.string.firmware_review_warning_body),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(state.acknowledged, role = Role.Checkbox) { actions.onAcknowledge(it) }
            .padding(vertical = spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Checkbox(checked = state.acknowledged, onCheckedChange = null)
        Text(stringResource(R.string.firmware_acknowledge), style = MaterialTheme.typography.bodyLarge)
    }
    when {
        !state.connected -> Text(stringResource(R.string.firmware_needs_watch), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.settleSeconds > 0 -> Text(
            stringResource(R.string.firmware_settling, state.settleSeconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// ── Running, done, failed ────────────────────────────────────────────────────

@Composable
private fun Running(state: FirmwareUiState) {
    val spacing = NormPlusTheme.spacing
    val p = state.progress
    Intro(stringResource(R.string.firmware_running_headline), stringResource(R.string.firmware_running_body))
    NormCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
            StateMark(SendState.Sending, showLabel = false)
            Text(stringResource(stageLabel(p?.step)), style = MaterialTheme.typography.titleLarge)
            if (p != null && p.step == OtaStep.DATA_STREAM && p.packetsTotal > 0) {
                ProgressBar(p.packetsCurrent.toFloat() / p.packetsTotal, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.firmware_pieces, countText(p.packetsCurrent), countText(p.packetsTotal), p.percentComplete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.remainingSeconds?.let { secs ->
                    Text(remainingText(secs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun remainingText(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.firmware_remaining_seconds, seconds.coerceAtLeast(1))
    else stringResource(R.string.firmware_remaining_minutes, (seconds + 30) / 60)

private fun stageLabel(step: OtaStep?): Int = when (step) {
    OtaStep.DATA_STREAM -> R.string.firmware_stage_sending
    OtaStep.CRC_VERIFY -> R.string.firmware_stage_checking
    OtaStep.REBOOT -> R.string.firmware_stage_restarting
    OtaStep.DONE -> R.string.firmware_stage_done
    else -> R.string.firmware_stage_preparing
}

@Composable
private fun Done() {
    Intro(stringResource(R.string.firmware_done_headline), stringResource(R.string.firmware_done_body))
    StateMark(SendState.Sent)
}

@Composable
private fun Failed(state: FirmwareUiState) {
    var details by remember { mutableStateOf(false) }
    Notice(
        title = stringResource(R.string.firmware_failed_title),
        tone = NoticeTone.Failed,
        body = stringResource(R.string.firmware_failed_body),
    )
    TextButton(onClick = { details = !details }) {
        Text(stringResource(if (details) R.string.firmware_hide_details else R.string.firmware_details))
    }
    if (details) {
        Text(
            stringResource(R.string.firmware_details_body, state.progress?.step?.name ?: "-", state.error ?: state.progress?.errorMessage ?: "-"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
