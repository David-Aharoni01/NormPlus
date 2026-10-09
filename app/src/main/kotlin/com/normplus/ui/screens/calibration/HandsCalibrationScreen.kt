package com.normplus.ui.screens.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.normplus.protocol.commands.HandMode
import com.normplus.ui.theme.Background
import com.normplus.ui.theme.OnSurfaceMuted
import com.normplus.ui.theme.Surface
import com.normplus.ui.theme.SurfaceHigh
import com.normplus.ui.theme.Teal

private fun HandMode.label() = when (this) {
    HandMode.HOUR -> "hour"
    HandMode.MINUTE -> "minute"
    HandMode.SECOND -> "second"
}

@Composable
fun HandsCalibrationScreen(viewModel: HandsCalibrationViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    // Arm calibration (speed up + unlock) once connected. Re-fires if connection arrives later.
    LaunchedEffect(state.isConnected) { viewModel.onStart() }
    // Always re-lock the hands if the user leaves without saving.
    DisposableEffect(Unit) { onDispose { viewModel.onLeave() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Calibrate Watch Hands", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }

        when {
            !state.isConnected -> CenteredMessage("Connect your watch to calibrate its hands.")
            state.done -> DoneView(onBack)
            else -> CalibrationBody(state, viewModel)
        }
    }
}

@Composable
private fun CalibrationBody(state: CalibrationUiState, vm: HandsCalibrationViewModel) {
    val hand = state.currentHand
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Intro / rationale.
        Card {
            Text(
                "Line each hand up to 12 o'clock. When you Save, the watch is set to the current " +
                    "time and drives the hands to show it — fixing any drift.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "${hand.label().replaceFirstChar { it.uppercase() }} hand · ${state.stepNumber} of ${state.total}",
            style = MaterialTheme.typography.labelLarge,
            color = Teal,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Move the ${hand.label()} hand to 12:00",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(24.dp))
        // Fine nudge.
        Text("Fine adjust", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NudgeButton(Icons.Default.Remove, "Back one step", Modifier.weight(1f)) { vm.nudge(clockwise = false) }
            NudgeButton(Icons.Default.Add, "Forward one step", Modifier.weight(1f)) { vm.nudge(clockwise = true) }
        }

        Spacer(Modifier.height(20.dp))
        // Continuous rotate (press and hold).
        Text("Hold to rotate", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldRotateButton("↺  CCW", Modifier.weight(1f), vm, clockwise = false)
            HoldRotateButton("CW  ↻", Modifier.weight(1f), vm, clockwise = true)
        }

        Spacer(Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.isFirst) {
                OutlinedButton(
                    onClick = { vm.back() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal),
                ) { Text("Back") }
            }
            Button(
                onClick = { if (state.isLast) vm.save() else vm.next() },
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black),
                enabled = !state.saving,
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text(if (state.isLast) "Save & sync" else "Next hand", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
    ) { Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun HoldRotateButton(
    label: String,
    modifier: Modifier,
    vm: HandsCalibrationViewModel,
    clockwise: Boolean,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .pointerInput(clockwise) {
                detectTapGestures(
                    onPress = {
                        vm.startRotate(clockwise)
                        // Suspends until the finger lifts or the gesture cancels.
                        tryAwaitRelease()
                        vm.stopRotate()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DoneView(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Teal, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Hands calibrated", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "The watch was set to the current time and the hands are locked in.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black),
        ) { Text("Done", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(16.dp),
    ) { content() }
}
