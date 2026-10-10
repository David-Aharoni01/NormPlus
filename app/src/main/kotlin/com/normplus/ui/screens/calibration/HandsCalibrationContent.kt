package com.normplus.ui.screens.calibration

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.normplus.R
import com.normplus.protocol.commands.HandMode
import com.normplus.ui.components.DialHand
import com.normplus.ui.components.FlowScaffold
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.SendState
import com.normplus.ui.components.StateMark
import com.normplus.ui.components.WatchDial
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import java.time.LocalTime

/** What the calibration's controls do; the route binds them to [HandsCalibrationViewModel]. */
class CalibrationActions(
    val onClose: () -> Unit = {},
    /** One small step of the hand that is moving (0xB6), clockwise or not. */
    val onNudge: (clockwise: Boolean) -> Unit = {},
    /** Start turning the hand (0xB8 START) as the finger goes down... */
    val onHoldStart: (clockwise: Boolean) -> Unit = {},
    /** ...and stop (0xB8 STOP) when it lifts or the gesture is cancelled. */
    val onHoldEnd: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onRestart: () -> Unit = {},
)

/**
 * Hands calibration (#105), drawn from [state] alone: the watch drawn as the hero with the
 * hand that is moving in violet and a mark at twelve, the step in the pill, the controls on
 * one card, the way on along the bottom.
 *
 * @param time the current time, where the hands stand once the watch has been set (the
 *   route passes the live time; tests a fixed one). While a hand is being lined up the dial
 *   shows where it is asked to go, not the time.
 */
@Composable
fun HandsCalibrationContent(state: CalibrationUiState, time: LocalTime, actions: CalibrationActions) {
    val phase = state.phase
    val step = if (phase == CalibrationPhase.Hand) {
        stringResource(R.string.calibration_step, state.stepNumber, state.total, stringResource(state.currentHand.label()))
    } else {
        null
    }
    FlowScaffold(
        title = stringResource(R.string.calibration_title),
        onClose = actions.onClose,
        step = step,
        actions = { BottomActions(state, actions) },
    ) {
        HeroDial(state, time)
        when (phase) {
            CalibrationPhase.NotConnected -> Intro(
                stringResource(R.string.calibration_offline_headline),
                stringResource(R.string.calibration_offline_body),
            )
            CalibrationPhase.Hand -> {
                val minute = state.currentHand == HandMode.MINUTE
                Intro(
                    stringResource(if (minute) R.string.calibration_minute_headline else R.string.calibration_hour_headline),
                    stringResource(if (minute) R.string.calibration_minute_body else R.string.calibration_hour_body),
                )
                Controls(state.currentHand, actions)
            }
            CalibrationPhase.Saving -> {
                Intro(
                    stringResource(R.string.calibration_saving_headline),
                    stringResource(R.string.calibration_saving_body),
                )
                StateMark(SendState.Sending)
            }
            CalibrationPhase.Done -> {
                Intro(
                    stringResource(R.string.calibration_done_headline),
                    stringResource(R.string.calibration_done_body),
                )
                StateMark(SendState.Sent)
            }
            CalibrationPhase.SaveFailed -> Notice(
                title = stringResource(R.string.calibration_failed_title),
                tone = NoticeTone.Failed,
                body = stringResource(R.string.calibration_failed_body),
            )
        }
    }
}

private fun HandMode.label(): Int = if (this == HandMode.HOUR) R.string.calibration_hour_hand else R.string.calibration_minute_hand

private fun HandMode.inline(): Int =
    if (this == HandMode.HOUR) R.string.calibration_hour_hand_inline else R.string.calibration_minute_hand_inline

// ── The bottom row ───────────────────────────────────────────────────────────

@Composable
private fun BottomActions(state: CalibrationUiState, actions: CalibrationActions) {
    when (state.phase) {
        CalibrationPhase.NotConnected, CalibrationPhase.Saving -> Unit
        CalibrationPhase.Hand -> {
            if (!state.isFirst) PillButton(stringResource(R.string.calibration_back), actions.onBack, tone = PillTone.Neutral)
            if (state.isLast) {
                PillButton(stringResource(R.string.calibration_save), actions.onSave, tone = PillTone.Primary)
            } else {
                PillButton(stringResource(R.string.calibration_next), actions.onNext, tone = PillTone.Primary)
            }
        }
        CalibrationPhase.Done -> PillButton(stringResource(R.string.calibration_done), actions.onClose, tone = PillTone.Primary)
        // Lining the hands up again needs the watch: until it is back, the banner says so.
        CalibrationPhase.SaveFailed -> PillButton(
            stringResource(R.string.calibration_start_again),
            actions.onRestart,
            tone = PillTone.Primary,
            enabled = state.isConnected,
        )
    }
}

// ── The parts ────────────────────────────────────────────────────────────────

/**
 * The watch drawn as the step's hero, with the screen's one glow. While a hand is lined up it
 * shows the goal: that hand in violet at twelve, the mark there, the other hand faded back
 * (the minute step leaves the hour hand at ten, where it is not being asked to be). Saving
 * sweeps both from twelve to [time], as the physical hands do; done shows [time]. Not
 * connected, it has no hands to show.
 */
@Composable
private fun ColumnScope.HeroDial(state: CalibrationUiState, time: LocalTime) {
    val phase = state.phase
    val minuteStep = state.currentHand == HandMode.MINUTE
    val shown = when (phase) {
        CalibrationPhase.Hand -> if (minuteStep) MINUTE_STEP_TIME else TWELVE
        CalibrationPhase.Saving, CalibrationPhase.Done -> time
        CalibrationPhase.NotConnected, CalibrationPhase.SaveFailed -> TWELVE
    }
    val highlight = when {
        phase != CalibrationPhase.Hand -> null
        minuteStep -> DialHand.Minute
        else -> DialHand.Hour
    }
    val spoken = when {
        phase == CalibrationPhase.NotConnected -> stringResource(R.string.calibration_dial_offline_spoken)
        highlight == DialHand.Minute -> stringResource(R.string.calibration_dial_minute_spoken)
        highlight == DialHand.Hour -> stringResource(R.string.calibration_dial_hour_spoken)
        else -> null
    }
    val describe = if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier
    Box(
        Modifier
            .fillMaxWidth(HERO_DIAL_WIDTH)
            .align(Alignment.CenterHorizontally)
            .padding(vertical = NormPlusTheme.spacing.s)
            .then(describe),
    ) {
        WatchDial(
            time = shown,
            highlight = highlight,
            showTwelveMark = phase == CalibrationPhase.Hand,
            showHands = phase != CalibrationPhase.NotConnected,
            modifier = Modifier.fillMaxWidth().heroGlow(),
        )
    }
}

/** A step's headline and what it means. */
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

/** The two ways to move the hand, on one card: a nudge per tap, and a turn for as long as it is held. */
@Composable
private fun Controls(hand: HandMode, actions: CalibrationActions) {
    val name = stringResource(hand.inline())
    NormCard(Modifier.fillMaxWidth()) {
        ControlRow(
            title = stringResource(R.string.calibration_nudge_title),
            body = stringResource(R.string.calibration_nudge_body),
        ) {
            TapControl(Icons.Rounded.Remove, stringResource(R.string.calibration_nudge_back, name)) { actions.onNudge(false) }
            TapControl(Icons.Rounded.Add, stringResource(R.string.calibration_nudge_forward, name)) { actions.onNudge(true) }
        }
        HorizontalDivider(
            Modifier.padding(vertical = NormPlusTheme.spacing.l),
            thickness = NormPlusTheme.spacing.hairline,
            color = NormPlusTheme.colors.cardHairline,
        )
        ControlRow(
            title = stringResource(R.string.calibration_hold_title),
            body = stringResource(R.string.calibration_hold_body),
        ) {
            // A turn is physical: these arrows are not mirrored right to left.
            HoldControl(Icons.Rounded.RotateLeft, stringResource(R.string.calibration_hold_back, name), clockwise = false, actions)
            HoldControl(Icons.Rounded.RotateRight, stringResource(R.string.calibration_hold_forward, name), clockwise = true, actions)
        }
    }
}

@Composable
private fun ControlRow(title: String, body: String, controls: @Composable () -> Unit) {
    val spacing = NormPlusTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) { controls() }
    }
}

/** A round control, larger than the 48 dp floor: the thumb is on it while the eye is on the watch. */
private val ControlSize @Composable get() = NormPlusTheme.spacing.touchTarget + NormPlusTheme.spacing.m

/** Tap: one nudge. Raised, with a violet glyph, as a tonal pill is. */
@Composable
private fun TapControl(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = NormPlusTheme.shapes.pill,
        color = NormPlusTheme.colors.raised,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(ControlSize).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
    }
}

/**
 * Press and hold: the hand turns from the moment the finger goes down until it lifts (or the
 * gesture is cancelled), and the control fills violet for as long as it is turning.
 */
@Composable
private fun HoldControl(icon: ImageVector, description: String, clockwise: Boolean, actions: CalibrationActions) {
    var held by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = NormPlusTheme.shapes.pill,
        color = if (held) scheme.primary else NormPlusTheme.colors.raised,
        contentColor = if (held) scheme.onPrimary else scheme.primary,
        modifier = Modifier
            .size(ControlSize)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(clockwise, actions) {
                detectTapGestures(
                    onPress = {
                        held = true
                        actions.onHoldStart(clockwise)
                        // Suspends until the finger lifts or the gesture is cancelled.
                        tryAwaitRelease()
                        held = false
                        actions.onHoldEnd()
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
    }
}

private const val HERO_DIAL_WIDTH = 0.62f
private val TWELVE: LocalTime = LocalTime.NOON
private val MINUTE_STEP_TIME: LocalTime = LocalTime.of(10, 0)
