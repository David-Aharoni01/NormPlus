package com.normplus.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.normplus.R
import com.normplus.ui.components.LargeTitleHeader
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.components.NavigationCapsule
import com.normplus.ui.components.NavigationCapsuleDefaults
import com.normplus.ui.components.ShellStatus
import com.normplus.ui.components.ShellStatusSlot
import com.normplus.ui.navigation.Destination
import com.normplus.ui.screens.history.HistoryRoute
import com.normplus.ui.screens.today.TodayRoute
import com.normplus.ui.screens.watch.WatchRoute
import com.normplus.ui.theme.NormMotion
import com.normplus.ui.theme.NormPlusTheme

/** The three tabs, in the navigation capsule's order. */
enum class ShellTab(
    val destination: Destination,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Today(Destination.Today, R.string.shell_tab_today, Icons.Rounded.Today, Icons.Outlined.Today),
    History(Destination.History, R.string.shell_tab_history, Icons.Rounded.BarChart, Icons.Outlined.BarChart),
    Watch(Destination.Watch, R.string.shell_tab_watch, Icons.Rounded.Watch, Icons.Outlined.Watch),
}

/**
 * The tabs' frame (#97), edge-to-edge on the ground: the large bold title of the tab with the
 * watch's status under it (the pill, or the banner in its place when something needs fixing)
 * and Sync at its end on Today; the tab's content, scrolling under the header and under the
 * floating navigation capsule. The header stays put when the tab changes, and collapses to a small
 * title as the tab's content scrolls (each tab keeps its own [headerStates]); only [content]
 * fades through. Stateless, for the screenshot tests; [TabsHost] drives it.
 *
 * @param content the tab, drawn under the header and the capsule, given the padding that keeps
 *   it clear of both: the header at its top, the capsule and the system's navigation bar at its
 *   bottom. Pass it to a list's contentPadding (with the gutter added).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScaffold(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    status: ShellStatus = LocalShellStatus.current,
    headerStates: Map<ShellTab, TopAppBarState> = rememberHeaderStates(),
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(headerStates.getValue(selected))
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        NavigationCapsuleDefaults.contentPadding.calculateBottomPadding()
    // The content runs under the header (transparent until something scrolls beneath it, so a
    // hero's glow shows through) and under the capsule; its padding keeps it clear of both.
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTitleHeader(
                title = stringResource(selected.label),
                scrollBehavior = scroll,
                windowInsets = WindowInsets.statusBars,
                actions = {
                    if (selected == ShellTab.Today) {
                        IconButton(onClick = onSync, enabled = syncEnabled) {
                            Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.shell_sync))
                        }
                    }
                },
                status = if (status.isEmpty) null else ({ ShellStatusSlot(status = status) }),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        // The padding below covers the system bars, so they are consumed for the tab: a tab
        // that also asks for statusBarsPadding() gets nothing twice.
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.systemBars)) {
                content(PaddingValues(top = inner.calculateTopPadding(), bottom = bottom))
            }
            NavigationCapsule(Modifier.align(Alignment.BottomCenter)) {
                ShellTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onSelect(tab) },
                        icon = { Icon(if (isSelected) tab.selectedIcon else tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationCapsuleDefaults.itemColors(),
                    )
                }
            }
        }
    }
}

/** One header state per tab, kept across tab changes and recreation, so each keeps its collapse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberHeaderStates(): Map<ShellTab, TopAppBarState> =
    ShellTab.entries.associateWith { rememberTopAppBarState() }

/**
 * The tabs and their own graph: Today (the start), History, Watch. Switching tabs keeps each
 * one's state and scroll (saved and restored), Back from History or Watch returns to Today, and
 * Back from Today leaves the app. Between tabs the content fades through; a cut with
 * animations removed.
 *
 * @param navigate opens a screen below the tabs (the app's graph).
 * @param onWatchForgotten Watch's "Forget this watch" is done: back to the first run.
 * @param onSync Today's Sync action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsHost(
    navigate: (Destination) -> Unit,
    onWatchForgotten: () -> Unit,
    onSync: () -> Unit,
) {
    val tabsNav = rememberNavController()
    val entry by tabsNav.currentBackStackEntryAsState()
    val selected = ShellTab.entries.firstOrNull { tab -> entry?.destination?.hasRoute(tab.destination::class) == true }
        ?: ShellTab.Today
    val status = LocalWatchStatus.current
    val removed = NormPlusTheme.animationsRemoved

    TabsScaffold(
        selected = selected,
        onSelect = { tab ->
            if (tab != selected) {
                tabsNav.navigate(tab.destination) {
                    popUpTo(tabsNav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        syncEnabled = status.isReady && !status.isSyncing,
        onSync = onSync,
    ) { contentPadding ->
        NavHost(
            navController = tabsNav,
            startDestination = Destination.Today,
            enterTransition = { NormMotion.fadeThrough(removed).targetContentEnter },
            exitTransition = { NormMotion.fadeThrough(removed).initialContentExit },
            popEnterTransition = { NormMotion.fadeThrough(removed).targetContentEnter },
            popExitTransition = { NormMotion.fadeThrough(removed).initialContentExit },
        ) {
            composable<Destination.Today> {
                TodayRoute(contentPadding = contentPadding, onOpenDay = { navigate(Destination.DayDetail(it)) })
            }
            composable<Destination.History> {
                HistoryRoute(contentPadding = contentPadding, onOpenDay = { navigate(Destination.DayDetail(it)) })
            }
            composable<Destination.Watch> {
                WatchRoute(
                    contentPadding = contentPadding,
                    onOpenNotificationApps = { navigate(Destination.NotificationApps) },
                    onOpenWatchScreens = { navigate(Destination.WatchScreens) },
                    onOpenHandsCalibration = { navigate(Destination.HandsCalibration) },
                    onOpenFirmwareUpdate = { navigate(Destination.FirmwareUpdate()) },
                    onOpenTechnical = { navigate(Destination.Technical) },
                    onWatchForgotten = onWatchForgotten,
                )
            }
        }
    }
}
