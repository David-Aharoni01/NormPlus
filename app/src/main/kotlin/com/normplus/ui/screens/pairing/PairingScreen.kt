@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui.screens.pairing

import com.normplus.ui.legacy.LegacyInk
import com.normplus.ui.legacy.LegacyOnAccent
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.normplus.ble.BleConnectionState
import com.normplus.ui.legacy.Background
import com.normplus.ui.legacy.OnSurfaceMuted
import com.normplus.ui.legacy.Surface
import com.normplus.ui.legacy.SurfaceVariant
import com.normplus.ui.legacy.Teal

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onConnected: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var macInput by remember { mutableStateOf("") }

    LaunchedEffect(state.isConnected) {
        if (state.isConnected) onConnected()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // Pulsing watch icon
        val pulse = rememberInfiniteTransition(label = "pulse")
        val scale by pulse.animateFloat(
            initialValue = 1f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "scale"
        )
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
                    .background(Teal.copy(alpha = 0.12f), CircleShape)
            )
            Icon(
                Icons.Default.Watch,
                contentDescription = null,
                tint = Teal,
                modifier = Modifier.size(64.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
        Text("Connect to Norm 2", style = MaterialTheme.typography.headlineMedium, color = LegacyInk)
        Spacer(Modifier.height(8.dp))

        val statusText = when (val cs = state.connectionState) {
            is BleConnectionState.Scanning -> "Scanning for devices…"
            is BleConnectionState.Connecting -> "Connecting…"
            is BleConnectionState.Discovering -> "Setting up…"
            is BleConnectionState.Ready -> when (state.bindStatus) {
                BindStatus.NotStarted, BindStatus.Binding -> "Pairing with the watch…"
                BindStatus.Done -> "Paired!"
                is BindStatus.Failed -> "The watch did not finish pairing"
            }
            is BleConnectionState.Error -> "Retrying… (${cs.retryCount}/3)"
            else -> if (state.scanResults.isEmpty()) "Tap below to enter MAC from watch QR" else "Tap a device to connect"
        }
        val busy = state.isScanning ||
            state.connectionState is BleConnectionState.Connecting ||
            (state.connectionState is BleConnectionState.Ready && state.bindStatus is BindStatus.Binding)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Teal, strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(statusText, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceMuted)
        }

        // The watch refused or ignored the bind handshake: say which step, and let the user
        // try again (the watch's own "Pairing Failed" screen clears itself) or go on without it.
        val bindFailure = state.bindStatus as? BindStatus.Failed
        if (bindFailure != null && state.connectionState is BleConnectionState.Ready) {
            Spacer(Modifier.height(12.dp))
            Text(bindFailure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { viewModel.bind() },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = LegacyOnAccent),
                ) { Text("Try again") }
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = { viewModel.continueWithoutBind() },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = LegacyInk),
                ) { Text("Continue anyway") }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Discovered devices via BLE scan
        if (state.scanResults.isNotEmpty()) {
            Text("Found Devices", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(state.scanResults) { device ->
                    DeviceItem(device = device, onClick = { viewModel.connectTo(device) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Divider + manual MAC / QR entry
        HorizontalDivider(color = SurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            "Or enter MAC / scan watch QR",
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "On your watch, find the QR code (Settings → About) and scan it, or type the MAC address (AA:BB:CC:DD:EE:FF).",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = macInput,
            onValueChange = { macInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AA:BB:CC:DD:EE:FF or paste QR text", color = OnSurfaceMuted) },
            singleLine = true,
            isError = state.macError != null,
            supportingText = state.macError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                if (macInput.isNotBlank()) viewModel.connectByQrOrMac(macInput)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Teal,
                unfocusedBorderColor = SurfaceVariant,
                focusedTextColor = LegacyInk,
                unfocusedTextColor = LegacyInk,
                cursorColor = Teal,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (macInput.isNotBlank()) viewModel.connectByQrOrMac(macInput) },
            modifier = Modifier.fillMaxWidth(),
            enabled = macInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = LegacyOnAccent),
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(SurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Watch, contentDescription = null, tint = Teal, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            @Suppress("MissingPermission")
            Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium, color = LegacyInk)
            Text(device.address, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
    }
}
