package com.normplus.ui.screens.notificationapps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.R
import com.normplus.status.Blocker
import com.normplus.status.StatusWords
import com.normplus.ui.components.FixItCard
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.components.SettingRow
import com.normplus.ui.components.SettingsDivider
import com.normplus.ui.theme.NormPlusTheme

/** What the screen can ask for. */
@Immutable
data class NotificationAppsActions(
    val onQueryChange: (String) -> Unit = {},
    val onShowSystemAppsChange: (Boolean) -> Unit = {},
    val onEnabledChange: (AppRow, Boolean) -> Unit = { _, _ -> },
    val onSuppressDuplicatesChange: (AppRow, Boolean) -> Unit = { _, _ -> },
    val onRetryLoad: () -> Unit = {},
    val onTurnOnAccess: () -> Unit = {},
)

/**
 * Notification apps, stateless (#104): under the large title, notification access first when
 * it is off (a fix-it with its reason), then search, "Show system apps", the count forwarding,
 * and one card per app: its icon, its label in its own direction, its switch; an app that
 * forwards opens to "Skip repeats of identical text". Skeleton cards while loading; a search
 * that matches nothing says what it looked for.
 *
 * @param accessOff the shared status's `Blocker.NotificationAccessOff`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationAppsContent(
    state: NotificationAppsUiState,
    accessOff: Boolean,
    actions: NotificationAppsActions,
    onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(
        title = stringResource(R.string.notificationapps_title),
        onBack = onBack,
        snackbarHost = snackbarHost,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = padding.calculateTopPadding() + spacing.s,
                bottom = padding.calculateBottomPadding() + spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            if (accessOff) {
                item(key = "access", contentType = "access") {
                    FixItCard(
                        title = stringResource(StatusWords.title(Blocker.NotificationAccessOff)),
                        reason = stringResource(StatusWords.reason(Blocker.NotificationAccessOff)),
                        actionLabel = stringResource(StatusWords.fixLabel(Blocker.NotificationAccessOff)),
                        onAction = actions.onTurnOnAccess,
                        modifier = Modifier.padding(bottom = spacing.s),
                    )
                }
            }

            // The search stays in reach while 400 apps scroll under it.
            stickyHeader(key = "search", contentType = "search") {
                SearchField(state.query, actions.onQueryChange)
            }

            item(key = "system", contentType = "system") {
                NormCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = spacing.xs)) {
                    SettingRow(
                        headline = stringResource(R.string.notificationapps_show_system),
                        supporting = stringResource(R.string.notificationapps_show_system_supporting),
                        modifier = Modifier.toggleable(
                            value = state.showSystemApps,
                            role = Role.Switch,
                            onValueChange = actions.onShowSystemAppsChange,
                        ),
                        trailing = { Switch(checked = state.showSystemApps, onCheckedChange = null) },
                    )
                }
            }

            item(key = "count", contentType = "count") {
                Text(
                    if (state.forwardingCount == 0) {
                        stringResource(R.string.notificationapps_forwarding_none)
                    } else {
                        pluralStringResource(R.plurals.notificationapps_forwarding, state.forwardingCount, state.forwardingCount)
                    },
                    style = NormPlusTheme.type.sectionTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = spacing.xs, end = spacing.xs, top = spacing.l, bottom = spacing.xs)
                        .semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }

            when {
                state.loading -> item(key = "loading", contentType = "loading") { SkeletonRows() }

                state.loadFailed && state.apps.isEmpty() -> item(key = "failed", contentType = "failed") {
                    Notice(
                        title = stringResource(R.string.notificationapps_load_failed),
                        tone = NoticeTone.Failed,
                        body = stringResource(R.string.notificationapps_load_failed_body),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = actions.onRetryLoad,
                    )
                }

                state.apps.isEmpty() -> item(key = "empty", contentType = "empty") {
                    NothingMatches(state.query, state.showSystemApps) { actions.onShowSystemAppsChange(true) }
                }

                else -> items(state.apps, key = { it.packageName }, contentType = { "app" }) { row ->
                    AppCard(row, actions.onEnabledChange, actions.onSuppressDuplicatesChange)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val spacing = NormPlusTheme.spacing
    val focus = LocalFocusManager.current
    val c = NormPlusTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = spacing.s),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(stringResource(R.string.notificationapps_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.notificationapps_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = NormPlusTheme.shapes.pill,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.card,
            unfocusedContainerColor = c.card,
            unfocusedBorderColor = c.cardHairline,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * One app: icon, label, switch; the whole row is the switch. Forwarding, it opens to its one
 * option. The label keeps its own direction (a Hebrew or Arabic name reads right to left) but
 * lines up at the start of the column with every other label.
 */
@Composable
private fun AppCard(
    row: AppRow,
    onEnabledChange: (AppRow, Boolean) -> Unit,
    onSuppressChange: (AppRow, Boolean) -> Unit,
) {
    val spacing = NormPlusTheme.spacing
    val iconSize = appIconSize()
    NormCard(Modifier.fillMaxWidth(), shape = NormPlusTheme.shapes.tile, contentPadding = PaddingValues()) {
        val on = stringResource(R.string.notificationapps_row_on)
        val off = stringResource(R.string.notificationapps_row_off)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = row.enabled, role = Role.Switch, onValueChange = { onEnabledChange(row, it) })
                .semantics { stateDescription = if (row.enabled) on else off }
                .heightIn(min = spacing.touchTarget + spacing.l)
                .padding(start = spacing.l, end = spacing.l, top = spacing.s, bottom = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(row)
            Spacer(Modifier.width(spacing.m))
            Text(
                row.label,
                style = ownDirection(MaterialTheme.typography.bodyLarge),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing.m))
            Switch(checked = row.enabled, onCheckedChange = null)
        }
        if (row.enabled) {
            SettingsDivider(Modifier.padding(start = iconSize + spacing.m))
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = row.suppressDuplicates,
                        role = Role.Switch,
                        onValueChange = { onSuppressChange(row, it) },
                    )
                    .heightIn(min = spacing.touchTarget + spacing.l)
                    .padding(start = spacing.l + iconSize + spacing.m, end = spacing.l, top = spacing.s, bottom = spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                    Text(
                        stringResource(R.string.notificationapps_skip_repeats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.notificationapps_skip_repeats_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(spacing.m))
                Switch(checked = row.suppressDuplicates, onCheckedChange = null)
            }
        }
    }
}

/** An app's icon slot: the launcher icon, rasterised off the main thread; its initial until it arrives. */
@Composable
private fun appIconSize() = NormPlusTheme.spacing.xxl + NormPlusTheme.spacing.s

@Composable
private fun AppIcon(row: AppRow) {
    val size = appIconSize()
    val icon = row.icon
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(NormPlusTheme.shapes.pill).background(NormPlusTheme.colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial(row.label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The label's first character (a whole code point, so any script), upper-cased. */
internal fun initial(label: String): String =
    if (label.isEmpty()) "?" else String(Character.toChars(label.codePointAt(0))).uppercase()

/**
 * [style] with the text's own direction (an app named in Hebrew reads right to left in an
 * English list), aligned to the layout's start so the labels still form one column.
 */
@Composable
private fun ownDirection(style: TextStyle): TextStyle = style.copy(
    textDirection = TextDirection.Content,
    textAlign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TextAlign.Left else TextAlign.Right,
)

/** Six still cards in the shape of the rows to come; nothing moves. TalkBack hears one sentence. */
@Composable
private fun SkeletonRows() {
    val spacing = NormPlusTheme.spacing
    val ink = NormPlusTheme.colors.raised
    val description = stringResource(R.string.notificationapps_loading)
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        listOf(0.55f, 0.38f, 0.7f, 0.45f, 0.6f, 0.32f).forEach { fraction ->
            NormCard(Modifier.fillMaxWidth(), shape = NormPlusTheme.shapes.tile, contentPadding = PaddingValues()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget + spacing.l)
                        .padding(horizontal = spacing.l, vertical = spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(appIconSize()).clip(NormPlusTheme.shapes.pill).background(ink))
                    Spacer(Modifier.width(spacing.m))
                    Box(Modifier.weight(1f)) {
                        Box(
                            Modifier.fillMaxWidth(fraction).height(spacing.m).clip(NormPlusTheme.shapes.pill).background(ink),
                        )
                    }
                }
            }
        }
    }
}

/** A search that matched nothing: what was looked for, and where else to look. */
@Composable
private fun NothingMatches(query: String, showingSystem: Boolean, onShowSystem: () -> Unit) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spacing.l, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(spacing.xxl))
        val searching = query.isNotBlank()
        Text(
            if (searching) stringResource(R.string.notificationapps_no_match, query.trim()) else stringResource(R.string.notificationapps_none),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (searching) {
            Text(
                stringResource(if (showingSystem) R.string.notificationapps_no_match_hint else R.string.notificationapps_no_match_system_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (!showingSystem) {
            PillButton(
                stringResource(R.string.notificationapps_show_system),
                onShowSystem,
                tone = PillTone.Tonal,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
    }
}
