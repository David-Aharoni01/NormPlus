package com.normplus.ui.screens.watchscreens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.normplus.R
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.components.SendState
import com.normplus.ui.components.StateMark
import com.normplus.ui.components.WatchScreen
import com.normplus.ui.components.WatchScreenThumbnail
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** What the screens order's controls do; the route binds them to [WatchScreensViewModel]. */
class WatchScreensActions(
    val onBack: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    val onRetry: () -> Unit = {},
    val onLeaveWithoutSending: () -> Unit = {},
)

/**
 * The order of the watch's own screens (#103): the face first and fixed, then each page as its
 * round preview, moved by dragging its handle (after a long press) or with the up and down
 * buttons, which TalkBack also offers as actions. The order is sent once, on Done.
 */
@Composable
fun WatchScreensContent(state: WatchScreensUiState, time: LocalTime, date: LocalDate, actions: WatchScreensActions) {
    val spacing = NormPlusTheme.spacing
    ScreenScaffold(
        title = stringResource(R.string.watchscreens_title),
        onBack = actions.onBack,
        actions = {
            TextButton(onClick = actions.onDone, enabled = state.send != SendState.Sending) {
                Text(stringResource(R.string.watchscreens_done))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = padding.calculateTopPadding() + spacing.s,
                bottom = padding.calculateBottomPadding() + spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.m)) {
                    Text(
                        stringResource(R.string.watchscreens_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        state.send == SendState.NotSent -> Notice(
                            title = stringResource(R.string.watchscreens_not_sent_title),
                            tone = NoticeTone.Failed,
                            body = stringResource(if (state.connected) R.string.watchscreens_not_sent_body else R.string.watchscreens_not_sent_offline),
                            actionLabel = stringResource(R.string.watchscreens_retry),
                            onAction = actions.onRetry,
                            secondaryLabel = stringResource(R.string.watchscreens_leave),
                            onSecondary = actions.onLeaveWithoutSending,
                        )
                        state.send != null -> StateMark(state.send)
                        !state.connected -> Text(
                            stringResource(R.string.watchscreens_offline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.readFailed -> Text(
                            stringResource(R.string.watchscreens_read_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "face") { FaceRow(time, date) }
            val pages = state.order.mapNotNull { WatchScreen.fromPageType(it) }
            itemsIndexed(pages, key = { _, s -> s.name }) { i, screen ->
                PageRow(screen, i, pages.size, time, date, actions)
            }
        }
    }
}

/** The face: always first, not movable. */
@Composable
private fun FaceRow(time: LocalTime, date: LocalDate) {
    NormCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
            WatchScreenThumbnail(WatchScreen.Face, number = 1, selected = true, time = time, date = date, size = ThumbSize, showName = false)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.watch_screen_face), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.watchscreens_face_fixed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One page: its preview, its name, up and down, and a drag handle. Dragging the handle moves
 * the row a place each time it passes half a row; the up and down buttons and TalkBack's
 * actions do the same one place at a time.
 */
@Composable
private fun PageRow(screen: WatchScreen, index: Int, count: Int, time: LocalTime, date: LocalDate, actions: WatchScreensActions) {
    val spacing = NormPlusTheme.spacing
    val name = stringResource(screen.label)
    val up = stringResource(R.string.watchscreens_move_up, name)
    val down = stringResource(R.string.watchscreens_move_down, name)
    var rowHeight by remember { mutableIntStateOf(1) }
    var drag by remember { mutableFloatStateOf(0f) }
    val position by rememberUpdatedState(index)
    val total by rememberUpdatedState(count)
    NormCard(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = drag }
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(up) { actions.onMove(index, index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction(down) { actions.onMove(index, index + 1); true })
                }
            },
        contentPadding = PaddingValues(horizontal = spacing.l, vertical = spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
            WatchScreenThumbnail(screen, number = index + 2, selected = false, time = time, date = date, size = ThumbSize, showName = false)
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onMove(index, index - 1) }, enabled = index > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = up)
            }
            IconButton(onClick = { actions.onMove(index, index + 1) }, enabled = index < count - 1) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = down)
            }
            Box(
                Modifier
                    .size(spacing.touchTarget)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragEnd = { drag = 0f },
                            onDragCancel = { drag = 0f },
                        ) { change, amount ->
                            change.consume()
                            drag += amount.y
                            val steps = (drag / rowHeight).roundToInt()
                            val from = position
                            val target = (from + steps).coerceIn(0, total - 1)
                            if (target != from) {
                                actions.onMove(from, target)
                                drag -= (target - from) * rowHeight
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DragHandle, contentDescription = null)
            }
        }
    }
}

private val ThumbSize = 44.dp
