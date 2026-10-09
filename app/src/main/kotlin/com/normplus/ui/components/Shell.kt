package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.ReversedColors

/*
 * The shell: the ink-blue top app bar with its quiet status line, the banner under it, and
 * the navigation bar's colours. #97 assembles them; every screen uses the same ones.
 */

/** What the quiet status line shows before its words. */
enum class StatusMark { None, Charging, Syncing }

/**
 * The quiet status, one line: "Norm 2 · 82% · synced 14:32", with a bolt while the watch
 * charges or the sending mark while a sync runs ("Reading sport records · 412 of 922").
 * It stays quiet; anything that needs fixing goes in the [ConnectionBanner] instead.
 */
@Composable
fun QuietStatusLine(text: String, modifier: Modifier = Modifier, mark: StatusMark = StatusMark.None) {
    val spacing = NormPlusTheme.spacing
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val markWord = when (mark) {
        StatusMark.None -> null
        StatusMark.Charging -> stringResource(R.string.status_charging)
        StatusMark.Syncing -> stringResource(R.string.status_syncing)
    }
    Row(
        modifier.clearAndSetSemantics {
            val spoken = text.replace(" · ", ", ")
            contentDescription = if (markWord != null) "$markWord, $spoken" else spoken
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mark) {
            StatusMark.None -> Unit
            StatusMark.Charging -> Icon(Icons.Rounded.Bolt, contentDescription = null, tint = ink, modifier = Modifier.size(spacing.markIcon))
            StatusMark.Syncing -> MarkGlyph(SendState.Sending, size = spacing.markIcon, tint = ink)
        }
        if (mark != StatusMark.None) Spacer(Modifier.width(spacing.xs))
        Text(text, style = NormPlusTheme.type.status, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The top app bar: printed on the ink-blue backing, the title with the quiet status line
 * under it. Icons and actions inside it come out reversed on their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NormTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusMark: StatusMark = StatusMark.None,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val c = NormPlusTheme.colors
    ReversedColors {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status != null) QuietStatusLine(status, mark = statusMark)
                }
            },
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = if (status != null) 72.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = c.plate,
                scrolledContainerColor = c.plate,
                navigationIconContentColor = c.onPlate,
                titleContentColor = c.onPlate,
                actionIconContentColor = c.onPlate,
            ),
            scrollBehavior = scrollBehavior,
        )
    }
}

/** The shell's colours for the parts #97 builds from stock Material components. */
object ShellDefaults {
    /** The navigation bar is printed on the backing, like the top app bar. */
    val navigationBarColor: Color
        @Composable get() = NormPlusTheme.colors.plate

    /** Selected: a sheet-white pill with an ink-blue icon. Unselected: the plate's quiet ink. */
    @Composable
    fun navigationBarItemColors(): NavigationBarItemColors {
        val c = NormPlusTheme.colors
        return NavigationBarItemDefaults.colors(
            selectedIconColor = c.onShellIndicator,
            selectedTextColor = c.onPlate,
            indicatorColor = c.shellIndicator,
            unselectedIconColor = c.onPlateVariant,
            unselectedTextColor = c.onPlateVariant,
        )
    }
}

/** How a [ConnectionBanner] reads. */
enum class BannerTone {
    /** Something is under way: scanning, connecting, setting up, reconnecting. */
    Working,
    /** A plain fact with a way on: "Your watch is not connected". */
    Notice,
    /** Amber: a blocker with a one-tap fix (Bluetooth off, a permission, the battery). */
    NeedsFixing,
    /** Error red: something failed. */
    Failed,
}

/**
 * The banner under the top app bar, in any connection state but Ready or with any blocker:
 * the state in words and its one fix ("Bluetooth is off · Turn on", "Reconnecting to your
 * watch… (attempt 3)"). The same banner on every tab and sub-screen. Changes are announced
 * politely to TalkBack.
 *
 * @param hint a second line, e.g. after three failed attempts: "Turning Bluetooth off and
 *   on usually helps".
 */
@Composable
fun ConnectionBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    val c = NormPlusTheme.colors
    val scheme = MaterialTheme.colorScheme
    val (container, content, markTint) = when (tone) {
        BannerTone.Working, BannerTone.Notice -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSecondaryContainer)
        BannerTone.NeedsFixing -> Triple(c.needsFixing, c.onNeedsFixing, c.needsFixingMark)
        BannerTone.Failed -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier
                .heightIn(min = spacing.touchTarget + spacing.s)
                .padding(start = spacing.gutter, end = spacing.s, top = spacing.s, bottom = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            when (tone) {
                BannerTone.Working -> MarkGlyph(SendState.Sending, size = spacing.icon * 0.8f, tint = markTint)
                BannerTone.Notice -> Icon(Icons.Outlined.Info, null, tint = markTint, modifier = Modifier.size(spacing.icon * 0.8f))
                BannerTone.NeedsFixing -> Icon(Icons.Rounded.WarningAmber, null, tint = markTint, modifier = Modifier.size(spacing.icon * 0.8f))
                BannerTone.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = markTint, modifier = Modifier.size(spacing.icon * 0.8f))
            }
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
