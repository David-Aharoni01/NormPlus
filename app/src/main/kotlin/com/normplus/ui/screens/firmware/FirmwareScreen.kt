@file:Suppress("DEPRECATION") // pre-redesign screen: ui/legacy until its rebuild (#96)

package com.normplus.ui.screens.firmware

import com.normplus.ui.legacy.LegacyInk
import com.normplus.ui.legacy.LegacyOnAccent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.normplus.protocol.ota.OtaProgress
import com.normplus.protocol.ota.OtaStep
import com.normplus.ui.legacy.Background
import com.normplus.ui.legacy.ErrorRed
import com.normplus.ui.legacy.HrRed
import com.normplus.ui.legacy.OnSurfaceMuted
import com.normplus.ui.legacy.SuccessGreen
import com.normplus.ui.legacy.Surface
import com.normplus.ui.legacy.SurfaceVariant
import com.normplus.ui.legacy.Teal

@Composable
fun FirmwareScreen(viewModel: FirmwareViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { u ->
            val name = context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(idx)
            } ?: u.lastPathSegment ?: "firmware.bin"
            viewModel.setCustomFile(u, name)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LegacyInk)
                }
                Text("Firmware", style = MaterialTheme.typography.headlineSmall, color = LegacyInk)
            }

            Spacer(Modifier.height(8.dp))

            // Version card
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(20.dp)
            ) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = Teal, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Watch", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                        Text(
                            if (state.watchVersion.isNotEmpty()) state.watchVersion else "—",
                            style = MaterialTheme.typography.headlineSmall, color = LegacyInk,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Bundled", style = MaterialTheme.typography.labelLarge, color = OnSurfaceMuted)
                        Text(viewModel.bundledVersion, style = MaterialTheme.typography.headlineSmall, color = Teal)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Bundled firmware section
            FirmwareSection(title = "Bundled Resources") {
                Text(
                    "Re-send the watch's original resource image (screens and fonts, ${viewModel.bundledVersion}). " +
                        "The main firmware is not touched. The watch's UI is unavailable until the update completes.",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.flashBundled() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = LegacyOnAccent),
                    enabled = !state.isFlashing,
                ) { Text("Re-flash Original Resources", style = MaterialTheme.typography.titleMedium) }
            }

            Spacer(Modifier.height(12.dp))

            // Custom firmware section
            FirmwareSection(title = "Custom Firmware") {
                OutlinedButton(
                    onClick = { filePicker.launch("application/octet-stream") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceMuted),
                    enabled = !state.isFlashing,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${state.customFileName ?: "Choose .bin file"}")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.flashCustom() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HrRed, contentColor = LegacyOnAccent),
                    enabled = state.customFileUri != null && !state.isFlashing,
                ) { Text("Flash Custom Firmware", style = MaterialTheme.typography.titleMedium) }
            }

            // OTA progress
            state.progress?.let { progress ->
                Spacer(Modifier.height(20.dp))
                OtaProgressCard(progress)
            }

            Spacer(Modifier.height(80.dp))
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun FirmwareSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LegacyInk)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun OtaProgressCard(progress: OtaProgress) {
    val steps = listOf(OtaStep.UPGRADE_MODE, OtaStep.BT_PARAM, OtaStep.INIT, OtaStep.SET_HEADER, OtaStep.DATA_STREAM, OtaStep.CRC_VERIFY, OtaStep.REBOOT)
    val stepLabels = mapOf(
        OtaStep.UPGRADE_MODE to "Upgrade Mode",
        OtaStep.BT_PARAM to "BT Params",
        OtaStep.INIT to "Initialize",
        OtaStep.SET_HEADER to "Set Header",
        OtaStep.DATA_STREAM to "Uploading",
        OtaStep.CRC_VERIFY to "Verify CRC",
        OtaStep.REBOOT to "Finishing",
        OtaStep.DONE to "Done",
        OtaStep.FAILED to "Failed",
    )

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface).padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Update Progress", style = MaterialTheme.typography.titleMedium, color = LegacyInk)
            when {
                progress.isDone -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
                progress.isFailed -> Icon(Icons.Default.Error, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(24.dp))
                else -> Text("${progress.percentComplete}%", style = MaterialTheme.typography.titleMedium, color = Teal)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Step indicator
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            steps.forEach { step ->
                val isDone = steps.indexOf(step) < steps.indexOf(progress.step)
                val isCurrent = step == progress.step
                val color = when {
                    progress.isDone -> SuccessGreen
                    isDone -> Teal
                    isCurrent -> if (progress.isFailed) ErrorRed else Teal
                    else -> SurfaceVariant
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            stepLabels[progress.step] ?: progress.step.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (progress.isFailed) ErrorRed else Teal,
        )

        if (progress.step == OtaStep.DATA_STREAM && progress.packetsTotal > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.percentComplete / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Teal,
                trackColor = SurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${progress.packetsCurrent} / ${progress.packetsTotal} packets",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMuted,
            )
        }

        progress.errorMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = ErrorRed)
        }
    }
}
