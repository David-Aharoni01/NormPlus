package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.normplus.R
import com.normplus.ui.theme.NormPlusTheme

/**
 * A full-screen flow: first run, hands calibration, the firmware update, the custom-file
 * update. The large-title header with a Close action and the step in a neutral pill under
 * the title ("Step 2 of 4", "Minute hand"), and the connection banner under that whenever the
 * shell has one (none in the first run); the step's content, scrolling; its pill actions
 * along the bottom, the way on at the end ([PillTone.Primary]) and the way back before it.
 *
 * Closing is the screen's decision: a flow that must not be left half-done (an update that is
 * sending) asks first, and also catches system Back with a BackHandler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    step: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = NormPlusTheme.spacing
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val banner = LocalShellStatus.current.banner
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTitleHeader(
                title = title,
                scrollBehavior = scroll,
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                // The step, and under it the connection banner whenever something needs fixing
                // (#97): a flow on the watch is the one place a dropped link matters most.
                status = if (step == null && banner == null) null else ({
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                        if (step != null) StatusPill(step, StatusKind.Neutral)
                        ShellBanner(banner = banner)
                    }
                }),
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.gutter, vertical = spacing.m),
                horizontalArrangement = Arrangement.spacedBy(spacing.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter, vertical = spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.l),
            content = content,
        )
    }
}
