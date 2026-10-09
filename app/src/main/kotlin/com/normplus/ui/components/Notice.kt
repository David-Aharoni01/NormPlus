package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme

/** The two kinds of notice. Neither is ever a bare red figure: always an icon and words in a container. */
enum class NoticeTone {
    /** Amber: something the person can put right in one tap. */
    NeedsFixing,
    /** Error red: something failed ("Stopped while sending pieces"). */
    Failed,
}

/**
 * A notice card: the icon, a title, why it matters, and the one action that deals with it.
 * [FixItCard] is the amber kind every fix-it on Watch and in first run uses.
 *
 * @param secondaryLabel / [onSecondary] a quieter second action ("Details", "Not now").
 */
@Composable
fun Notice(
    title: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    val c = NormPlusTheme.colors
    val scheme = MaterialTheme.colorScheme
    val container = if (tone == NoticeTone.NeedsFixing) c.needsFixing else scheme.errorContainer
    val content = if (tone == NoticeTone.NeedsFixing) c.onNeedsFixing else scheme.onErrorContainer
    val mark = if (tone == NoticeTone.NeedsFixing) c.needsFixingMark else scheme.error
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NormPlusTheme.shapes.notice,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(start = spacing.l, top = spacing.m, end = spacing.s, bottom = spacing.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                Icon(
                    if (tone == NoticeTone.NeedsFixing) Icons.Rounded.WarningAmber else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = mark,
                    modifier = Modifier.padding(top = spacing.xxs).size(spacing.icon),
                )
                Column(Modifier.weight(1f).padding(top = spacing.xxs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(onClick = onSecondary, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                            Text(secondaryLabel)
                        }
                    }
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

/**
 * A fix-it: a blocker with its one-tap fix, for everyone ("Notification access is off ·
 * Norm+ needs it to send notifications to your watch · Turn on").
 */
@Composable
fun FixItCard(
    title: String,
    reason: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) = Notice(
    title = title,
    tone = NoticeTone.NeedsFixing,
    modifier = modifier,
    body = reason,
    actionLabel = actionLabel,
    onAction = onAction,
    onDismiss = onDismiss,
)
