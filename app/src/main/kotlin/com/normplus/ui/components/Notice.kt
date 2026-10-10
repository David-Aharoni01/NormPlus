package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme

/** The two kinds of notice. Neither is ever a bare coloured figure: always an icon and words. */
enum class NoticeTone {
    /** Amber: something the person can put right in one tap. */
    NeedsFixing,
    /** Red: something failed ("Stopped while sending pieces"). */
    Failed,
}

/**
 * A notice card, tinted in its state colour: the icon in a round badge, a title, why it
 * matters, and the one pill action that deals with it, with an optional quieter second one.
 * [FixItCard] is the amber kind every fix-it on Watch and in first run uses.
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
    val fix = tone == NoticeTone.NeedsFixing
    val container = if (fix) c.fixContainer else scheme.errorContainer
    val content = if (fix) c.onFixContainer else scheme.onErrorContainer
    val badge = if (fix) c.fixBanner else c.failBanner
    val onBadge = if (fix) c.onFixBanner else c.onFailBanner
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NormPlusTheme.shapes.card,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(start = spacing.l, top = spacing.l, end = spacing.s, bottom = spacing.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                Surface(shape = NormPlusTheme.shapes.pill, color = badge, contentColor = onBadge) {
                    Box(Modifier.size(spacing.xxl + spacing.xs), contentAlignment = Alignment.Center) {
                        Icon(
                            if (fix) Icons.Rounded.WarningAmber else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(spacing.smallIcon + spacing.xxs),
                        )
                    }
                }
                Column(
                    Modifier.weight(1f).padding(top = spacing.xs, end = if (onDismiss == null) spacing.s else spacing.xxs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
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
                    Modifier.fillMaxWidth().padding(top = spacing.m, end = spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(spacing.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        PillButton(
                            secondaryLabel,
                            onSecondary,
                            colorsOverride = ButtonDefaults.textButtonColors(contentColor = content),
                        )
                    }
                    PillButton(
                        actionLabel,
                        onAction,
                        colorsOverride = ButtonDefaults.buttonColors(containerColor = badge, contentColor = onBadge),
                    )
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
