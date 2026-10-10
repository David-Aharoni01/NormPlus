package com.normplus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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

/** The shell's parts: the large-title header and its status pill, the banners, the capsule. */
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
    fun headers() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                LargeTitleHeader(
                    title = "Today",
                    windowInsets = WindowInsets(0),
                    actions = { IconButton(onClick = {}) { Icon(Icons.Rounded.Sync, contentDescription = "Sync") } },
                    status = { StatusPill("Norm 2 · 82% · synced 14:32", StatusKind.Fine) },
                )
                LargeTitleHeader(
                    title = "Notification apps",
                    windowInsets = WindowInsets(0),
                    navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                    status = { StatusPill("Norm 2 · as of 09:12", StatusKind.Offline) },
                )
            }
        }
    }

    @Test
    fun statusPills() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                StatusPill("Norm 2 · 82% · synced 14:32", StatusKind.Fine)
                StatusPill("Norm 2 · 64% · synced 09:12", StatusKind.Charging)
                StatusPill("Reading sport records · 412 of 922", StatusKind.Syncing)
                StatusPill("Norm 2 · as of 09:12", StatusKind.Offline)
                StatusPill("Step 2 of 4", StatusKind.Neutral)
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
                ConnectionBanner(
                    "Battery optimisation is on",
                    BannerTone.NeedsFixing,
                    hint = "Android may stop Norm+ in the background",
                    actionLabel = "Allow",
                    onAction = {},
                )
                ConnectionBanner("Norm+ was stopped from its notification", BannerTone.Failed, actionLabel = "Start", onAction = {})
            }
        }
    }

    @Test
    fun navigationCapsule() = paparazzi.snapshot {
        ComponentFrame {
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.s)) {
                listOf(0, 2).forEach { selected ->
                    NavigationCapsule {
                        val items = listOf(
                            Triple("Today", Icons.Rounded.Today, Icons.Outlined.Today),
                            Triple("History", Icons.Rounded.BarChart, Icons.Outlined.BarChart),
                            Triple("Watch", Icons.Rounded.Watch, Icons.Outlined.Watch),
                        )
                        items.forEachIndexed { i, (label, filled, outlined) ->
                            NavigationBarItem(
                                selected = i == selected,
                                onClick = {},
                                icon = { Icon(if (i == selected) filled else outlined, contentDescription = null) },
                                label = { Text(label) },
                                colors = NavigationCapsuleDefaults.itemColors(),
                            )
                        }
                    }
                }
            }
        }
    }
}
