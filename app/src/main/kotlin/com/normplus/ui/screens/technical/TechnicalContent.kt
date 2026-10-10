package com.normplus.ui.screens.technical

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.normplus.R
import com.normplus.status.Link
import com.normplus.status.SyncState
import com.normplus.status.WatchStatus
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.components.SettingRow
import com.normplus.ui.components.SettingsDivider
import com.normplus.ui.components.SettingsSection
import com.normplus.ui.components.countText
import com.normplus.ui.components.rememberClockFormatter
import com.normplus.ui.theme.NormPlusTheme

/** What Technical's controls do; the route binds them. */
class TechnicalActions(
    val onBack: () -> Unit = {},
    val onStartService: () -> Unit = {},
    val onStartOnBoot: (Boolean) -> Unit = {},
    val onChooseFile: () -> Unit = {},
)

/**
 * Technical (#107), at the bottom of Watch: the details ordinary users need not see, in the same
 * face and palette as everywhere else (raw strings in tabular figures, never a terminal look).
 * The connection service, the link, the watch's address and versions, the last sync, what Norm+
 * holds, and the update from a file, which opens the same deliberate firmware flow (#106).
 */
@Composable
fun TechnicalContent(state: TechnicalUiState, status: WatchStatus, actions: TechnicalActions, clock: (Long) -> String = rememberClockFormatter()) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(title = stringResource(R.string.technical_title), onBack = actions.onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = spacing.gutter)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SettingsSection(stringResource(R.string.technical_section_connection)) {
                SettingRow(
                    headline = stringResource(R.string.technical_service),
                    supporting = stringResource(if (state.serviceRunning) R.string.technical_running else R.string.technical_stopped),
                    trailing = if (state.serviceRunning) null else ({
                        PillButton(stringResource(R.string.technical_start), actions.onStartService, tone = PillTone.Tonal)
                    }),
                )
                SettingsDivider()
                SettingRow(stringResource(R.string.technical_link), supporting = stringResource(linkWords(status.link)))
                SettingsDivider()
                SettingRow(
                    stringResource(R.string.technical_last_connected),
                    supporting = if (state.lastConnectedEpochMs > 0) clock(state.lastConnectedEpochMs) else stringResource(R.string.technical_never),
                )
                SettingsDivider()
                SettingRow(
                    headline = stringResource(R.string.technical_start_on_boot),
                    supporting = stringResource(R.string.technical_start_on_boot_body),
                    modifier = Modifier.toggleable(state.startOnBoot, role = Role.Switch) { actions.onStartOnBoot(it) },
                    trailing = { Switch(state.startOnBoot, onCheckedChange = null) },
                )
            }
            SettingsSection(stringResource(R.string.technical_section_watch)) {
                Raw(stringResource(R.string.technical_address), state.address ?: stringResource(R.string.technical_none))
                SettingsDivider()
                Raw(stringResource(R.string.technical_version), state.version.ifEmpty { stringResource(R.string.technical_not_read) })
            }
            SettingsSection(stringResource(R.string.technical_section_sync)) {
                SettingRow(stringResource(R.string.technical_last_sync), supporting = syncWords(status, clock))
                SettingsDivider()
                SettingRow(
                    stringResource(R.string.technical_records),
                    supporting = stringResource(
                        R.string.technical_records_body,
                        countText(state.sportRecords),
                        countText(state.heartRateRecords),
                        countText(state.sleepSessions),
                    ),
                )
            }
            SettingsSection(stringResource(R.string.technical_section_update)) {
                SettingRow(
                    headline = stringResource(R.string.technical_update_file),
                    supporting = stringResource(R.string.technical_update_file_body),
                    modifier = Modifier.clickable(role = Role.Button, onClick = actions.onChooseFile),
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                )
            }
        }
    }
}

/** A raw string from the watch: tabular figures, the theme's own face. */
@Composable
private fun Raw(label: String, value: String) {
    SettingRow(
        headline = label,
        supporting = null,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun linkWords(link: Link): Int = when (link) {
    Link.NoWatch -> R.string.technical_link_no_watch
    Link.Disconnected -> R.string.technical_link_disconnected
    Link.Scanning -> R.string.technical_link_scanning
    Link.Connecting -> R.string.technical_link_connecting
    Link.SettingUp -> R.string.technical_link_setting_up
    is Link.Reconnecting -> R.string.technical_link_reconnecting
    Link.Ready -> R.string.technical_link_ready
}

@Composable
private fun syncWords(status: WatchStatus, clock: (Long) -> String): String = when (val s = status.sync) {
    is SyncState.Running -> stringResource(R.string.technical_sync_running, countText(s.done), countText(s.total))
    is SyncState.Finished ->
        if (s.complete) stringResource(R.string.technical_sync_complete, clock(s.atEpochMs))
        else stringResource(R.string.technical_sync_short, clock(s.atEpochMs), s.problems.joinToString("; ") { it.detail })
    SyncState.Idle -> status.lastSyncEpochMs?.let { stringResource(R.string.technical_sync_complete, clock(it)) }
        ?: stringResource(R.string.technical_never)
}
