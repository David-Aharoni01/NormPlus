package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The shell's parts: the top app bar and its quiet status, the banner, the navigation bar. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(Parameterized::class)
class ShellSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun topAppBar() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) {
                NormTopAppBar(
                    title = "Today",
                    status = "Norm 2 · 82% · synced 14:32",
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Sync, contentDescription = "Sync") } },
                )
                NormTopAppBar(title = "Today", status = "Norm 2 · 64% · synced 09:12", statusMark = StatusMark.Charging)
                NormTopAppBar(title = "Today", status = "Reading sport records · 412 of 922", statusMark = StatusMark.Syncing)
                NormTopAppBar(
                    title = "Notification apps",
                    navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
            }
        }
    }

    @Test
    fun banners() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                ConnectionBanner("Connecting to your watch…", BannerTone.Working)
                ConnectionBanner(
                    "Reconnecting to your watch… (attempt 3)",
                    BannerTone.Working,
                    hint = "Turning Bluetooth off and on usually helps",
                )
                ConnectionBanner("Your watch is not connected", BannerTone.Notice, actionLabel = "Connect", onAction = {})
                ConnectionBanner("Bluetooth is off", BannerTone.NeedsFixing, actionLabel = "Turn on", onAction = {})
                ConnectionBanner("Norm+ was stopped from its notification", BannerTone.Failed, actionLabel = "Start", onAction = {})
            }
        }
    }

    @Test
    fun navigationBar() = paparazzi.snapshot {
        ComponentFrame {
            NavigationBar(containerColor = ShellDefaults.navigationBarColor) {
                val items = listOf("Today" to Icons.Outlined.CalendarToday, "History" to Icons.Outlined.BarChart, "Watch" to Icons.Outlined.Watch)
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = i == 0,
                        onClick = {},
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = ShellDefaults.navigationBarItemColors(),
                    )
                }
            }
        }
    }
}
