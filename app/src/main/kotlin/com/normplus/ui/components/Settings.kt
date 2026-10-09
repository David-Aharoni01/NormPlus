package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme

/**
 * A group of settings rows, as Watch lists them: a heading in the date line's tracked
 * capitals ("NOTIFICATIONS"), then the rows on one sheet, divided by hairlines
 * ([SettingsDivider]).
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = NormPlusTheme.spacing
    Column(modifier) {
        if (title != null) {
            Text(
                title.uppercase(currentLocale()),
                style = NormPlusTheme.type.dateLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = spacing.gutter, end = spacing.gutter, top = spacing.xl, bottom = spacing.s)
                    .semantics { heading() },
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = NormPlusTheme.colors.sheet,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(content = content)
        }
    }
}

/** The hairline between two rows of a [SettingsSection], indented to the text. */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = NormPlusTheme.spacing.gutter),
        thickness = NormPlusTheme.spacing.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One setting, sent to the watch the moment it changes (#102): the row carries its own
 * [SendState] mark under its words. "Not sent" offers Retry (and Dismiss) on the row itself.
 *
 * The control (a Switch, a value) goes in [trailing]; make the whole row the touch target
 * through [modifier] (`toggleable`, `clickable`). Rows are at least 56 dp tall (Material's
 * one-line list item), so every target is past 48 dp.
 *
 * @param supporting the row's value or explanation.
 * @param asOf when the value shown is the last one read rather than live: "09:12", printed
 *   "as of 09:12" (reading settings back is unverified on the physical watch, #1).
 * @param enabled false while the watch is not connected: the row dims and says
 *   [disabledReason] ("Connect your watch to change this").
 */
@Composable
fun SettingRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    sendState: SendState? = null,
    asOf: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val reason = disabledReason ?: stringResource(R.string.setting_needs_watch)
    ListItem(
        modifier = modifier,
        headlineContent = { Text(headline) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                val words = listOfNotNull(supporting, asOf?.let { stringResource(R.string.setting_as_of, it) })
                if (words.isNotEmpty()) Text(words.joinToString(" · "))
                if (!enabled) {
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                } else if (sendState != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        StateMark(sendState)
                        if (sendState == SendState.NotSent) {
                            if (onRetry != null) {
                                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                            }
                            if (onDismiss != null) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
                                }
                            }
                        }
                    }
                }
            }
        },
        leadingContent = leading,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = NormPlusTheme.colors.sheet,
            headlineColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = DISABLED),
            supportingColor = scheme.onSurfaceVariant,
            leadingIconColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = DISABLED),
            trailingIconColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = DISABLED),
        ),
    )
}

/** Material's disabled-content alpha. */
private const val DISABLED = 0.38f
