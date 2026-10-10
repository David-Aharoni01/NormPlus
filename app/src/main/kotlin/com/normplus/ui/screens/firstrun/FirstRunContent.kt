package com.normplus.ui.screens.firstrun

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import com.normplus.R
import com.normplus.status.Blocker
import com.normplus.status.StatusWords
import com.normplus.ui.components.FixItCard
import com.normplus.ui.components.FlowScaffold
import com.normplus.ui.components.MarkGlyph
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.ProgressBar
import com.normplus.ui.components.SendState
import com.normplus.ui.components.StatusKind
import com.normplus.ui.components.StatusPill
import com.normplus.ui.components.WatchDial
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import java.time.LocalTime

/** What the first run's controls do; the route binds them to the ViewModel and the shell's fixes. */
class FirstRunActions(
    val onClose: () -> Unit = {},
    val onNext: () -> Unit = {},
    /** Asks for [Ask] (Android's dialog or settings page). */
    val onAsk: (Ask) -> Unit = {},
    val onNotNowBattery: () -> Unit = {},
    val onTurnOnBluetooth: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onConnectTo: (FoundWatch) -> Unit = {},
    val onAddressChange: (String) -> Unit = {},
    val onConnectByAddress: () -> Unit = {},
    val onRetryConnect: () -> Unit = {},
    val onChooseAnotherWatch: () -> Unit = {},
    val onRetryBind: () -> Unit = {},
    val onContinueWithoutBind: () -> Unit = {},
    val onOpenNotificationApps: () -> Unit = {},
)

/**
 * The first run, drawn from [state] alone (#98): one step at a time on the [FlowScaffold],
 * "Step N of 8" in its pill, the way on along the bottom. No pill or banner about the link:
 * the connection is this flow's own business, shown in its Connect and Bind steps.
 *
 * @param time where the drawn watch's hands stand (the route passes the live time; tests a fixed one).
 */
@Composable
fun FirstRunContent(state: FirstRunUiState, time: LocalTime, actions: FirstRunActions) {
    val step = stringResource(R.string.firstrun_step, state.step.number, FirstRunStep.COUNT)
    val title = when (state.step) {
        FirstRunStep.Welcome -> R.string.firstrun_welcome_title
        FirstRunStep.Bluetooth -> R.string.firstrun_bluetooth_title
        FirstRunStep.Find -> R.string.firstrun_find_title
        FirstRunStep.Connect -> when (state.connect) {
            is ConnectPhase.Pairing, ConnectPhase.PairingClosed -> R.string.firstrun_connect_pair_title
            else -> R.string.firstrun_connect_title
        }
        FirstRunStep.Bind -> R.string.firstrun_bind_title
        FirstRunStep.Notifications -> R.string.firstrun_notifications_title
        FirstRunStep.Calls -> R.string.firstrun_calls_title
        FirstRunStep.Battery -> R.string.firstrun_battery_title
    }
    FlowScaffold(
        title = stringResource(title),
        onClose = actions.onClose,
        step = step,
        actions = { BottomActions(state, actions) },
    ) {
        when (state.step) {
            FirstRunStep.Welcome -> WelcomeStep(time)
            FirstRunStep.Bluetooth -> BluetoothStep(state, actions)
            FirstRunStep.Find -> FindStep(state, actions)
            FirstRunStep.Connect -> ConnectStep(state, actions)
            FirstRunStep.Bind -> BindStep(state, time, actions)
            FirstRunStep.Notifications -> NotificationsStep(state, actions)
            FirstRunStep.Calls -> AskStep(
                headline = R.string.firstrun_calls_headline,
                body = R.string.firstrun_calls_body,
                denied = R.string.firstrun_calls_denied.takeIf { state.denied(Ask.Calls) },
                onAskAgain = { actions.onAsk(Ask.Calls) },
            )
            FirstRunStep.Battery -> AskStep(
                headline = R.string.firstrun_battery_headline,
                body = R.string.firstrun_battery_body,
                denied = R.string.firstrun_battery_denied.takeIf { state.denied(Ask.Battery) },
                onAskAgain = { actions.onAsk(Ask.Battery) },
            )
        }
    }
}

// ── The bottom row ───────────────────────────────────────────────────────────

@Composable
private fun BottomActions(state: FirstRunUiState, actions: FirstRunActions) {
    @Composable
    fun primary(text: Int, onClick: () -> Unit) = PillButton(stringResource(text), onClick, tone = PillTone.Primary)

    @Composable
    fun quiet(text: Int, onClick: () -> Unit) = PillButton(stringResource(text), onClick, tone = PillTone.Quiet)

    /** A permission step: the way past it, and Allow until it has been refused, then Continue. */
    @Composable
    fun ask(ask: Ask, pass: Int, allow: Int = R.string.firstrun_allow, onPass: () -> Unit = actions.onNext) {
        if (state.denied(ask)) {
            primary(R.string.firstrun_continue, actions.onNext)
        } else {
            quiet(pass, onPass)
            primary(allow) { actions.onAsk(ask) }
        }
    }

    when (state.step) {
        FirstRunStep.Welcome -> primary(R.string.firstrun_welcome_action, actions.onNext)
        // The one required permission: no way past it but allowing it (Close leaves the app).
        FirstRunStep.Bluetooth -> primary(R.string.firstrun_bluetooth_action) { actions.onAsk(Ask.Bluetooth) }
        FirstRunStep.Find -> if (state.scan != ScanState.Scanning && state.grants.bluetooth && state.bluetoothOn) {
            PillButton(stringResource(R.string.firstrun_find_again), actions.onScan, tone = PillTone.Neutral)
        }
        FirstRunStep.Connect -> Unit // its failures carry their own actions
        FirstRunStep.Bind -> if (state.bind.done) primary(R.string.firstrun_continue, actions.onNext)
        FirstRunStep.Notifications -> when (state.notificationPart) {
            NotificationPart.Post -> ask(Ask.Notifications, R.string.firstrun_not_now)
            NotificationPart.Access -> ask(Ask.NotificationAccess, R.string.firstrun_not_now, R.string.firstrun_notifications_access_action)
            NotificationPart.Apps -> if (state.appsOpened) {
                quiet(R.string.firstrun_notifications_apps_again, actions.onOpenNotificationApps)
                primary(R.string.firstrun_continue, actions.onNext)
            } else {
                quiet(R.string.firstrun_not_now, actions.onNext)
                primary(R.string.firstrun_notifications_apps_action, actions.onOpenNotificationApps)
            }
        }
        FirstRunStep.Calls -> ask(Ask.Calls, R.string.firstrun_skip)
        FirstRunStep.Battery -> ask(Ask.Battery, R.string.firstrun_not_now, onPass = actions.onNotNowBattery)
    }
}

// ── The steps ────────────────────────────────────────────────────────────────

/** A step's headline and its reason. */
@Composable
private fun Intro(headline: String, body: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
        Text(headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The watch drawn live, as the step's hero, with the screen's one glow behind it. */
@Composable
private fun ColumnScope.HeroDial(time: LocalTime) {
    WatchDial(
        time = time,
        modifier = Modifier
            .fillMaxWidth(HERO_DIAL_WIDTH)
            .align(Alignment.CenterHorizontally)
            .padding(vertical = NormPlusTheme.spacing.s)
            .heroGlow(),
    )
}

/** How much of the width the drawn watch takes on the Welcome and Bind steps. */
private const val HERO_DIAL_WIDTH = 0.62f

@Composable
private fun ColumnScope.WelcomeStep(time: LocalTime) {
    HeroDial(time)
    Intro(stringResource(R.string.firstrun_welcome_headline), stringResource(R.string.firstrun_welcome_body))
}

@Composable
private fun BluetoothStep(state: FirstRunUiState, actions: FirstRunActions) {
    Intro(stringResource(R.string.firstrun_bluetooth_headline), stringResource(R.string.firstrun_bluetooth_body))
    if (state.denied(Ask.Bluetooth)) {
        FixItCard(
            title = stringResource(R.string.firstrun_bluetooth_denied_title),
            reason = stringResource(R.string.firstrun_bluetooth_denied_body),
            actionLabel = stringResource(R.string.firstrun_bluetooth_action),
            onAction = { actions.onAsk(Ask.Bluetooth) },
        )
    }
}

@Composable
private fun FindStep(state: FirstRunUiState, actions: FirstRunActions) {
    val spacing = NormPlusTheme.spacing
    if (!state.bluetoothOn) {
        FixItCard(
            title = stringResource(StatusWords.title(Blocker.BluetoothOff)),
            reason = stringResource(StatusWords.reason(Blocker.BluetoothOff)),
            actionLabel = stringResource(StatusWords.fixLabel(Blocker.BluetoothOff)),
            onAction = actions.onTurnOnBluetooth,
        )
    }
    if (state.bluetoothOn) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionTitle(stringResource(R.string.firstrun_find_nearby))
                if (state.scan == ScanState.Scanning) StatusPill(stringResource(R.string.firstrun_find_scanning), StatusKind.Syncing)
            }
            if (state.found.isNotEmpty()) {
                FoundList(state.found, actions.onConnectTo)
            } else if (state.scan == ScanState.Finished) {
                Text(
                    stringResource(R.string.firstrun_find_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.scan == ScanState.Failed) {
                Notice(
                    title = stringResource(R.string.firstrun_find_failed_title),
                    tone = NoticeTone.Failed,
                    body = stringResource(R.string.firstrun_find_failed_body),
                    actionLabel = stringResource(R.string.firstrun_find_again),
                    onAction = actions.onScan,
                )
            }
        }
    }
    ManualEntry(state, actions)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = NormPlusTheme.type.sectionTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

/** The watches the scan found: name and address, a row each; a tap connects. */
@Composable
private fun FoundList(found: List<FoundWatch>, onConnect: (FoundWatch) -> Unit) {
    NormCard(contentPadding = PaddingValues(vertical = NormPlusTheme.spacing.xs)) {
        found.forEachIndexed { i, watch ->
            if (i > 0) HorizontalDivider(color = NormPlusTheme.colors.cardHairline, modifier = Modifier.padding(horizontal = NormPlusTheme.spacing.l))
            val name = watch.name ?: stringResource(R.string.firstrun_find_unnamed)
            val spoken = stringResource(R.string.firstrun_find_watch_spoken, name, watch.address)
            NormCardRow(
                headline = name,
                supporting = watch.address,
                spoken = spoken,
                onClick = { onConnect(watch) },
            )
        }
    }
}

@Composable
private fun NormCardRow(headline: String, supporting: String, spoken: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick, role = Role.Button)
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Button },
        headlineContent = { Text(headline, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            // An address reads left to right in any language.
            Text(supporting, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
        },
        leadingContent = { Icon(Icons.Rounded.Watch, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = scheme.onSurface,
            supportingColor = scheme.onSurfaceVariant,
            leadingIconColor = scheme.primary,
            trailingIconColor = scheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun ManualEntry(state: FirstRunUiState, actions: FirstRunActions) {
    val spacing = NormPlusTheme.spacing
    Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(spacing.m)) {
        SectionTitle(stringResource(R.string.firstrun_find_manual))
        Text(
            stringResource(R.string.firstrun_find_manual_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.address,
            onValueChange = actions.onAddressChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.firstrun_find_field)) },
            placeholder = { Text(stringResource(R.string.firstrun_find_field_example)) },
            singleLine = true,
            isError = state.addressInvalid,
            supportingText = if (state.addressInvalid) ({ Text(stringResource(R.string.firstrun_find_invalid)) }) else null,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            shape = NormPlusTheme.shapes.tile,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (state.address.isNotBlank()) actions.onConnectByAddress() }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(
                stringResource(R.string.firstrun_find_connect),
                actions.onConnectByAddress,
                tone = PillTone.Tonal,
                enabled = state.address.isNotBlank() && state.grants.bluetooth && state.bluetoothOn,
            )
        }
    }
}

@Composable
private fun ConnectStep(state: FirstRunUiState, actions: FirstRunActions) {
    val spacing = NormPlusTheme.spacing
    val watchName = state.watch?.name ?: stringResource(R.string.firstrun_connect_your_watch)
    when (val phase = state.connect) {
        is ConnectPhase.Pairing -> NormCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
                Intro(stringResource(R.string.firstrun_connect_pair_headline, watchName), stringResource(R.string.firstrun_connect_pair_body))
                ProgressBar(phase.secondsLeft / PAIRING_WINDOW_SECONDS.toFloat())
                Text(
                    pluralStringResource(R.plurals.firstrun_connect_pair_seconds, phase.secondsLeft, phase.secondsLeft),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ConnectPhase.PairingClosed -> Notice(
            title = stringResource(R.string.firstrun_connect_pair_closed_title),
            tone = NoticeTone.Failed,
            body = stringResource(R.string.firstrun_connect_pair_closed_body),
            actionLabel = stringResource(R.string.firstrun_try_again),
            onAction = actions.onRetryConnect,
            secondaryLabel = stringResource(R.string.firstrun_another_watch),
            onSecondary = actions.onChooseAnotherWatch,
        )
        ConnectPhase.GaveUp -> Notice(
            title = stringResource(R.string.firstrun_connect_gave_up_title, watchName),
            tone = NoticeTone.Failed,
            body = stringResource(R.string.firstrun_connect_gave_up_body),
            actionLabel = stringResource(R.string.firstrun_try_again),
            onAction = actions.onRetryConnect,
            secondaryLabel = stringResource(R.string.firstrun_another_watch),
            onSecondary = actions.onChooseAnotherWatch,
        )
        else -> Text(
            stringResource(R.string.firstrun_connect_honest),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Stages(state, watchName)
}

/** The three things connecting does, each with the marks every screen uses. */
@Composable
private fun Stages(state: FirstRunUiState, watchName: String) {
    val phase = state.connect
    val pair: Pair<SendState, String> = when {
        state.paired -> SendState.Sent to stringResource(R.string.firstrun_connect_stage_paired)
        phase is ConnectPhase.Pairing -> SendState.Sending to stringResource(R.string.firstrun_connect_stage_waiting_dialog)
        phase == ConnectPhase.PairingClosed -> SendState.NotSent to stringResource(R.string.firstrun_connect_pair_closed_title)
        else -> SendState.Waiting to watchName
    }
    val connect: Pair<SendState, String?> = when (phase) {
        is ConnectPhase.SettingUp -> SendState.Sent to null
        ConnectPhase.GaveUp -> SendState.NotSent to null
        is ConnectPhase.Connecting -> SendState.Sending to
            if (phase.attempt > 0) pluralStringResource(R.plurals.firstrun_connect_stage_retry, phase.attempt, phase.attempt)
            else stringResource(R.string.firstrun_connect_stage_trying)
        else -> SendState.Waiting to null
    }
    val setup: SendState = if (phase is ConnectPhase.SettingUp) SendState.Sending else SendState.Waiting
    NormCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
            StageRow(stringResource(R.string.firstrun_connect_stage_pair), pair.second, pair.first)
            StageRow(stringResource(R.string.firstrun_connect_stage_connect), connect.second, connect.first)
            StageRow(stringResource(R.string.firstrun_connect_stage_setup), null, setup)
        }
    }
}

@Composable
private fun StageRow(name: String, detail: String?, mark: SendState) {
    val spacing = NormPlusTheme.spacing
    val spoken = listOfNotNull(name, detail, stringResource(markWord(mark))).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkGlyph(mark, size = spacing.icon)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = if (mark == SendState.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun markWord(mark: SendState): Int = when (mark) {
    SendState.Sending -> R.string.mark_sending
    SendState.Sent -> R.string.mark_sent
    SendState.NotSent -> R.string.mark_not_sent
    SendState.Waiting -> R.string.mark_waiting
}

@Composable
private fun ColumnScope.BindStep(state: FirstRunUiState, time: LocalTime, actions: FirstRunActions) {
    HeroDial(time)
    when (val bind = state.bind) {
        is BindPhase.Failed -> Notice(
            title = stringResource(R.string.firstrun_bind_failed_title),
            tone = NoticeTone.Failed,
            body = stringResource(R.string.firstrun_bind_failed_body),
            actionLabel = stringResource(R.string.firstrun_try_again),
            onAction = actions.onRetryBind,
            secondaryLabel = stringResource(R.string.firstrun_bind_continue_anyway),
            onSecondary = actions.onContinueWithoutBind,
        )
        BindPhase.Bound, BindPhase.AlreadyBound -> MarkedLine(
            SendState.Sent,
            stringResource(if (bind == BindPhase.Bound) R.string.firstrun_bind_bound else R.string.firstrun_bind_already),
        )
        else -> {
            MarkedLine(SendState.Sending, stringResource(R.string.firstrun_bind_working))
        }
    }
    if (state.bind !is BindPhase.Failed) {
        Text(
            stringResource(R.string.firstrun_bind_binding),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A headline led by its state mark ("✓ Your watch is set up"). */
@Composable
private fun MarkedLine(mark: SendState, text: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; heading() },
        horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkGlyph(mark, size = NormPlusTheme.spacing.icon)
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun NotificationsStep(state: FirstRunUiState, actions: FirstRunActions) {
    when (state.notificationPart) {
        NotificationPart.Post -> AskStep(
            headline = R.string.firstrun_notifications_post_headline,
            body = R.string.firstrun_notifications_post_body,
            denied = R.string.firstrun_notifications_post_denied.takeIf { state.denied(Ask.Notifications) },
            onAskAgain = { actions.onAsk(Ask.Notifications) },
        )
        NotificationPart.Access -> {
            AskStep(
                headline = R.string.firstrun_notifications_access_headline,
                body = R.string.firstrun_notifications_access_body,
                denied = R.string.firstrun_notifications_access_denied.takeIf { state.denied(Ask.NotificationAccess) },
                onAskAgain = { actions.onAsk(Ask.NotificationAccess) },
            )
            Text(
                stringResource(R.string.firstrun_notifications_access_restricted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NotificationPart.Apps -> Intro(
            stringResource(R.string.firstrun_notifications_apps_headline),
            stringResource(R.string.firstrun_notifications_apps_body),
        )
    }
}

/** A permission's step: what it is for, and once refused, what that means and Ask again. */
@Composable
private fun AskStep(headline: Int, body: Int, denied: Int?, onAskAgain: () -> Unit) {
    Intro(stringResource(headline), stringResource(body))
    if (denied != null) {
        Notice(
            title = stringResource(denied),
            tone = NoticeTone.NeedsFixing,
            actionLabel = stringResource(R.string.firstrun_ask_again),
            onAction = onAskAgain,
        )
    }
}
