package com.normplus.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.normplus.ui.theme.Background
import com.normplus.ui.theme.ErrorRed
import com.normplus.ui.theme.OnSurfaceMuted
import com.normplus.ui.theme.Surface
import com.normplus.ui.theme.SurfaceVariant
import com.normplus.ui.theme.Teal

/**
 * Notification whitelist picker — an Android-Settings-style installed-app list where the user
 * explicitly chooses which apps may push notifications to the watch. Apps not switched on here are
 * never forwarded (see `NotificationWhitelist`).
 *
 * Also hosts the notification-listener permission flow: without that grant nothing is intercepted
 * at all, so the card sits at the top of this screen and is re-checked on every resume (the user
 * leaves the app to grant it in system Settings).
 */
@Composable
fun NotificationRulesScreen(viewModel: NotificationRulesViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // The listener grant is changed in system Settings, i.e. while we're stopped — re-read on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshListenerState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                Text("Notification Apps", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }

            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (!state.listenerEnabled) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        ListenerPermissionCard(
                            onGrantClick = {
                                // Some ROMs lack the listener-settings activity — never crash the screen.
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    SearchField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Show system apps", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Text(
                                if (state.enabledCount == 0) "No apps forwarding yet"
                                else "${state.enabledCount} app${if (state.enabledCount == 1) "" else "s"} forwarding",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceMuted,
                            )
                        }
                        Switch(
                            checked = state.showSystemApps,
                            onCheckedChange = viewModel::setShowSystemApps,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Teal),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                when {
                    state.loading -> item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Teal, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        }
                    }

                    state.apps.isEmpty() -> item {
                        Text(
                            if (state.query.isBlank()) "No apps found."
                            else "No apps match \"${state.query}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceMuted,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }

                    else -> items(state.apps, key = { it.packageName }) { row ->
                        Spacer(Modifier.height(8.dp))
                        AppRuleCard(
                            row = row,
                            onEnabledChange = { viewModel.setEnabled(row, it) },
                            onMuteChange = { viewModel.setMuteGroupChats(row, it) },
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun ListenerPermissionCard(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Notification access is off", style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Norm+ can't read your notifications yet, so nothing will reach the watch. " +
                "Grant notification access, then come back — your choices below are remembered.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onGrantClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.Black),
        ) { Text("Open Notification Access", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search apps", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Teal,
            unfocusedBorderColor = SurfaceVariant,
            cursorColor = Teal,
            focusedLeadingIconColor = Teal,
            unfocusedLeadingIconColor = OnSurfaceMuted,
            focusedTrailingIconColor = OnSurfaceMuted,
            unfocusedTrailingIconColor = OnSurfaceMuted,
            focusedPlaceholderColor = OnSurfaceMuted,
            unfocusedPlaceholderColor = OnSurfaceMuted,
            focusedContainerColor = Surface,
            unfocusedContainerColor = Surface,
        ),
    )
}

@Composable
private fun AppRuleCard(
    row: AppRuleRow,
    onEnabledChange: (Boolean) -> Unit,
    onMuteChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable { onEnabledChange(!row.enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = row.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Teal),
            )
        }

        if (row.enabled) {
            Spacer(Modifier.height(4.dp))
            // "Vibrate on first" used to live here; it never controlled vibration (it gated
            // sending, which the whitelist switch above now does) so showing it would be a
            // no-op control. See NotificationRuleEntity.vibrateOnFirst.
            RuleToggle("Skip repeats of identical text", row.muteGroupChats, onMuteChange)
        }
    }
}

/** Pre-rasterised launcher icon, with a letter-tile placeholder until the icon batch arrives. */
@Composable
private fun AppIcon(row: AppRuleRow) {
    val icon = row.icon
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun RuleToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Switch(
            checked = checked, onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Teal),
        )
    }
}
