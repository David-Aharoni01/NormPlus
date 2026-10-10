package com.normplus.ui.screens.notificationapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.status.Blocker
import com.normplus.status.WatchStatus
import com.normplus.ui.components.LocalShellStatus
import com.normplus.ui.shell.LocalWatchStatus
import com.normplus.ui.shell.ShellSamples
import com.normplus.ui.shell.shellStatus
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Made-up apps, with icons of our own drawing: a round tile in a colour of its own. */
private object AppsSamples {
    private fun icon(argb: Long): ImageBitmap {
        val size = 110
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 30f, 30f, Paint().apply { color = Color(argb) })
        canvas.drawCircle(Offset(size / 2f, size / 2f), size / 4.5f, Paint().apply { color = Color(0xCCFFFFFF) })
        canvas.drawOval(Rect(size * 0.42f, size * 0.42f, size * 0.58f, size * 0.58f), Paint().apply { color = Color(argb) })
        return bitmap
    }

    private fun row(pkg: String, label: String, argb: Long?, enabled: Boolean = false, suppress: Boolean = true) =
        AppRow(pkg, label, argb?.let(::icon), enabled, vibrateOnFirst = true, suppressDuplicates = suppress)

    val rows = listOf(
        row("org.chat.signal", "Chat", 0xFF3A76F0, enabled = true),
        row("il.co.messages", "הודעות", 0xFF2E7D5B, enabled = true, suppress = false),
        row("ae.calendar", "التقويم", 0xFFC2410C, enabled = true),
        row("com.example.bank", "Bank", 0xFF0F766E),
        row("org.example.calendar", "Calendar", 0xFF7C3AED),
        row("com.example.mail", "Mail", null),
        row("com.example.longname", "A rather long app name that does not fit on one line", 0xFF9333EA),
        row("com.example.maps", "Maps", 0xFF15803D),
        row("com.example.news", "News", null),
        row("com.example.photos", "Photos", 0xFFDB2777),
    )

    val list = NotificationAppsUiState(loading = false, forwardingCount = 3, apps = rows)

    /** 400 apps: the list builds only what is on the screen. */
    val many = list.copy(
        apps = rows + (1..390).map { AppRow("com.example.app$it", "App $it", null, false, true, true) },
    )

    val accessOff = ShellSamples.ready.copy(blockers = listOf(Blocker.NotificationAccessOff))
}

/**
 * Notification apps (#104) on the whole Pixel 8 screen: every state the brief lists (§6), in
 * light, dark and font scale 1.3, and right to left. Hebrew and Arabic labels keep their own
 * direction in every layout.
 */
@RunWith(Parameterized::class)
class NotificationAppsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant, component = false)

    /** Access off, the apps still loading: the fix-it first, then skeleton cards. */
    @Test
    fun accessOffLoading() = paparazzi.snapshot {
        Screen(NotificationAppsUiState(loading = true), AppsSamples.accessOff)
    }

    /** Access off with the list: the fix-it stays first. */
    @Test
    fun accessOffList() = paparazzi.snapshot { Screen(AppsSamples.list, AppsSamples.accessOff) }

    /** Access on: three apps forwarding, each open to its option; one with repeats let through. */
    @Test
    fun list() = paparazzi.snapshot { Screen(AppsSamples.list, ShellSamples.ready) }

    @Test
    fun noneForwardingYet() = paparazzi.snapshot {
        Screen(
            AppsSamples.list.copy(forwardingCount = 0, apps = AppsSamples.rows.map { it.copy(enabled = false) }),
            ShellSamples.ready,
        )
    }

    @Test
    fun manyApps() = paparazzi.snapshot { Screen(AppsSamples.many, ShellSamples.ready) }

    /** A search that matched nothing, with system apps hidden: say so, offer to show them. */
    @Test
    fun noMatch() = paparazzi.snapshot {
        Screen(AppsSamples.list.copy(query = "Telegrm", apps = emptyList()), ShellSamples.ready)
    }

    @Test
    fun noMatchWithSystemApps() = paparazzi.snapshot {
        Screen(AppsSamples.list.copy(query = "Telegrm", showSystemApps = true, apps = emptyList()), ShellSamples.ready)
    }

    @Test
    fun loadFailed() = paparazzi.snapshot {
        Screen(AppsSamples.list.copy(apps = emptyList(), loadFailed = true), ShellSamples.ready)
    }

    /** Not connected: the shell's banner in the pill's place; the list works as ever. */
    @Test
    fun watchDisconnected() = paparazzi.snapshot {
        Screen(AppsSamples.list, ShellSamples.ready.copy(link = com.normplus.status.Link.Disconnected))
    }

    @Test
    fun rightToLeft() = paparazzi.snapshot {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Screen(AppsSamples.list, AppsSamples.accessOff)
        }
    }

    @Composable
    private fun Screen(state: NotificationAppsUiState, status: WatchStatus) {
        NormPlusTheme(animationsRemoved = true) {
            CompositionLocalProvider(
                LocalWatchStatus provides status,
                LocalShellStatus provides shellStatus(status, onFix = {}, today = ShellSamples.today),
            ) {
                NotificationAppsContent(
                    state = state,
                    accessOff = Blocker.NotificationAccessOff in status.blockers,
                    actions = NotificationAppsActions(),
                    onBack = {},
                )
            }
        }
    }
}
