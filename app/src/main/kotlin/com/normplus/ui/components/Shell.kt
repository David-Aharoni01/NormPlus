package com.normplus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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

/*
 * The shell's parts: the large-title header with its status pill, the state banner that
 * replaces the pill's quiet when something needs fixing, and the floating navigation capsule.
 * #97 assembles them; every screen uses the same ones.
 */

/** What the status pill says about the watch, which sets its colour and glyph. */
enum class StatusKind {
    /** Connected and well: green, with a tick ("Norm 2 · 82% · synced 14:32"). */
    Fine,
    /** Connected and charging: green, with a bolt. */
    Charging,
    /** A sync is running: violet, with the sending arc ("Reading sport records · 412 of 922"). */
    Syncing,
    /** Not connected; the figures are the last known ("Norm 2 · as of 09:12"): grey, link off. */
    Offline,
    /** Plain words with no state (a flow's step): grey, no glyph. */
    Neutral,
}

/**
 * The status pill under every screen's title: one tinted line about the watch. It stays
 * quiet; when something needs fixing, the [ConnectionBanner] says it instead. TalkBack hears
 * the glyph's word first ("Charging, Norm 2, 64%, synced 09:12").
 */
@Composable
fun StatusPill(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    val (container, content) = when (kind) {
        StatusKind.Fine, StatusKind.Charging -> c.fineContainer to c.fine
        StatusKind.Syncing -> scheme.primaryContainer to (if (NormPlusTheme.isDark) scheme.primary else scheme.onPrimaryContainer)
        StatusKind.Offline, StatusKind.Neutral -> c.raised to scheme.onSurfaceVariant
    }
    val markWord = when (kind) {
        StatusKind.Charging -> stringResource(R.string.status_charging)
        StatusKind.Syncing -> stringResource(R.string.status_syncing)
        StatusKind.Offline -> stringResource(R.string.status_offline)
        else -> null
    }
    val spoken = text.replace(" · ", ", ")
    TintedPill(
        container = container,
        content = content,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (markWord != null) "$markWord, $spoken" else spoken
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        val size = NormPlusTheme.spacing.smallIcon
        when (kind) {
            StatusKind.Fine -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(size), tint = content)
            StatusKind.Charging -> Icon(Icons.Rounded.Bolt, null, Modifier.size(size), tint = content)
            StatusKind.Syncing -> SendingGlyph(size, content)
            StatusKind.Offline -> Icon(Icons.Rounded.LinkOff, null, Modifier.size(size), tint = content)
            StatusKind.Neutral -> Unit
        }
        PillText(text)
    }
}

/**
 * The large-title header at the head of every screen: Material's large top app bar with a
 * big bold title, collapsing to a small one on scroll when given a [scrollBehavior] (connect
 * its nestedScrollConnection to the screen's scrolling content). Under the title sits
 * [status]: a [StatusPill], or a [ConnectionBanner] when something needs fixing.
 *
 * The header is transparent over the ground, so the glow behind a hero shows through; once
 * content scrolls under it, it takes the ground's colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeTitleHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    status: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val type = NormPlusTheme.type
    val spacing = NormPlusTheme.spacing
    val scrolled = scrollBehavior?.state?.let { maxOf(it.collapsedFraction, it.overlappedFraction.coerceIn(0f, 1f)) } ?: 0f
    val fill = lerp(Color.Transparent, scheme.background, scrolled)
    Column(modifier.fillMaxWidth().background(fill)) {
        // Material's large app bar sets its title in headlineMedium and the collapsed one in
        // titleLarge: here they are Dark Dial's title cuts.
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(headlineMedium = type.screenTitle, titleLarge = type.screenTitleCollapsed),
            shapes = MaterialTheme.shapes,
        ) {
            LargeTopAppBar(
                title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = navigationIcon,
                actions = actions,
                windowInsets = windowInsets,
                collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
                expandedHeight = HeaderExpandedHeight,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = scheme.onSurface,
                    titleContentColor = scheme.onSurface,
                    actionIconContentColor = scheme.primary,
                ),
                scrollBehavior = scrollBehavior,
            )
        }
        if (status != null) {
            Row(Modifier.padding(start = spacing.gutter, end = spacing.gutter, bottom = spacing.m)) { status() }
        }
    }
}

/** Title row plus breathing room: Material's 152 dp less the space the pill takes below it. */
private val HeaderExpandedHeight = 120.dp

/** How a [ConnectionBanner] reads. */
enum class BannerTone {
    /** Something is under way: scanning, connecting, setting up, reconnecting. Violet, tinted. */
    Working,
    /** A plain fact with a way on: "Your watch is not connected". Grey, raised. */
    Notice,
    /** A blocker with a one-tap fix (Bluetooth off, a permission, the battery). Amber, filled. */
    NeedsFixing,
    /** Something failed. Red, filled. */
    Failed,
}

/**
 * The status pill grown into a full-width rounded banner in its state colour: in any
 * connection state but Ready, or with any blocker, it names the state and its one fix
 * ("Bluetooth is off · Turn on", "Reconnecting to your watch… (attempt 3)"). The same banner
 * on every tab and sub-screen. The louder the fill, the more it asks: a tinted violet while
 * something is under way, filled amber for a fix, filled red for a failure. Every tone has
 * its own glyph and words. Changes are announced politely to TalkBack.
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
    val (container, content) = when (tone) {
        BannerTone.Working -> scheme.primaryContainer to scheme.onPrimaryContainer
        BannerTone.Notice -> c.raised to scheme.onSurface
        BannerTone.NeedsFixing -> c.fixBanner to c.onFixBanner
        BannerTone.Failed -> c.failBanner to c.onFailBanner
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = NormPlusTheme.shapes.banner,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier
                .heightIn(min = spacing.touchTarget + spacing.l)
                .padding(start = spacing.l, end = spacing.s, top = spacing.m, bottom = spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.m),
        ) {
            val glyph = spacing.icon
            when (tone) {
                BannerTone.Working -> SendingGlyph(glyph, if (NormPlusTheme.isDark) scheme.primary else content)
                BannerTone.Notice -> Icon(Icons.Outlined.Info, null, Modifier.size(glyph), tint = scheme.onSurfaceVariant)
                BannerTone.NeedsFixing -> Icon(Icons.Rounded.WarningAmber, null, Modifier.size(glyph))
                BannerTone.Failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(glyph))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(message, style = MaterialTheme.typography.titleSmall)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            if (actionLabel != null && onAction != null) {
                val filled = tone == BannerTone.NeedsFixing || tone == BannerTone.Failed
                PillButton(
                    text = actionLabel,
                    onClick = onAction,
                    tone = PillTone.Primary,
                    colorsOverride = if (filled) invertedPillColors(container, content) else null,
                )
            }
        }
    }
}

/**
 * The floating navigation capsule: Material's [NavigationBar], with its semantics and its
 * 48 dp items, in a rounded raised capsule that floats above the content, clear of the
 * screen's edges and of the system navigation bar. Fill it with NavigationBarItems coloured
 * by [NavigationCapsuleDefaults.itemColors]: the selected tab violet on its tinted pill.
 *
 * Content under it must leave [NavigationCapsuleDefaults.contentPadding] free at the bottom.
 */
@Composable
fun NavigationCapsule(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val spacing = NormPlusTheme.spacing
    val c = NormPlusTheme.colors
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = spacing.gutter, end = spacing.gutter, bottom = spacing.capsuleGap),
        shape = NormPlusTheme.shapes.pill,
        color = c.raised,
        border = BorderStroke(spacing.hairline, c.cardHairline),
        shadowElevation = CapsuleShadow,
    ) {
        NavigationBar(
            modifier = Modifier.height(spacing.capsuleHeight),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0),
            content = content,
        )
    }
}

private val CapsuleShadow = 8.dp

/** The navigation capsule's colours and the room it takes. */
object NavigationCapsuleDefaults {
    /** Selected: violet icon and words on the violet-tinted pill. Unselected: the quiet text colour. */
    @Composable
    fun itemColors(): NavigationBarItemColors {
        val scheme = MaterialTheme.colorScheme
        return NavigationBarItemDefaults.colors(
            selectedIconColor = if (NormPlusTheme.isDark) scheme.primary else scheme.onPrimaryContainer,
            selectedTextColor = scheme.primary,
            indicatorColor = scheme.primaryContainer,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        )
    }

    /** What content scrolling under the capsule leaves free at its end (plus the system bar's inset). */
    val contentPadding: PaddingValues
        @Composable get() = NormPlusTheme.spacing.let { PaddingValues(bottom = it.capsuleHeight + it.capsuleGap * 2) }
}
