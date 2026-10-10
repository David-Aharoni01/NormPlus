package com.normplus.ui.screens.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.normplus.domain.model.DndSettings
import com.normplus.domain.model.WatchSettings
import com.normplus.protocol.commands.SwitchSettingCommand
import com.normplus.status.Blocker
import com.normplus.status.Link
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.components.SendState
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellSamples
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Watch (#102) on the whole Pixel 8 screen, in light, dark and font scale 1.3: live, offline
 * (as of the last read), the per-row send marks, the fix-its, and further down the list.
 */
@RunWith(Parameterized::class)
class WatchSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries

        private val mask = SwitchSettingCommand.BIT_CALL or SwitchSettingCommand.BIT_SMS or
            SwitchSettingCommand.BIT_SOCIAL or SwitchSettingCommand.BIT_RAISE_WAKE or
            SwitchSettingCommand.BIT_HEART_RATE_MONITOR or SwitchSettingCommand.BIT_GOAL_ACHIEVED

        val live = WatchUiState(
            connected = true,
            settings = WatchSettings(brightness = 60, screenTimeoutSeconds = 10, switchMask = mask, dnd = DndSettings(true, 22, 0, 7, 0)),
            readAtEpochMs = ShellSamples.syncedToday,
            stepGoal = 7_000,
            forwardingApps = 3,
        )
        private val time: LocalTime = LocalTime.of(10, 9)
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    @Test
    fun live() = paparazzi.snapshot { Screen(live, ShellSamples.ready) }

    /** Not connected: the rows dim and say the values are as of the last read. */
    @Test
    fun offline() = paparazzi.snapshot {
        Screen(live.copy(connected = false), ShellSamples.ready.copy(link = Link.Disconnected))
    }

    /** Further down: each row with its own mark (sending, sent, not sent with Retry). */
    @Test
    fun sendMarks() = paparazzi.snapshot {
        Screen(
            live.copy(
                sends = mapOf(
                    SettingKey.Calls to SendState.Sent,
                    SettingKey.Sms to SendState.Sending,
                    SettingKey.Social to SendState.NotSent,
                ),
            ),
            ShellSamples.ready,
            scrollTo = 2,
        )
    }

    /** The fix-its, battery optimisation with its "Not now". */
    @Test
    fun fixIts() = paparazzi.snapshot {
        Screen(
            live,
            ShellSamples.ready.copy(blockers = listOf(Blocker.BatteryOptimisationOn, Blocker.NotificationAccessOff)),
            scrollTo = 1,
        )
    }

    @Test
    fun healthAndMore() = paparazzi.snapshot {
        Screen(live.copy(sends = mapOf(SettingKey.StepGoal to SendState.Sent)), ShellSamples.ready, scrollTo = 4)
    }

    @Composable
    private fun Screen(state: WatchUiState, status: WatchStatus, scrollTo: Int = 0) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                // The shell paints the ground in the app; here the test does.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                WatchContent(
                    state = state,
                    status = status,
                    time = time,
                    date = ShellSamples.today,
                    contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                    actions = WatchActions(),
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTo),
                    clock = { "14:32" },
                )
                }
            }
        }
    }
}
