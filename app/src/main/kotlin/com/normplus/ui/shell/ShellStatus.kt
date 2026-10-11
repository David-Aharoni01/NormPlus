package com.normplus.ui.shell

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.normplus.R
import com.normplus.domain.usecase.SyncStage
import com.normplus.status.Fix
import com.normplus.status.Link
import com.normplus.status.Severity
import com.normplus.status.StatusWords
import com.normplus.status.SyncState
import com.normplus.status.WatchStatus
import com.normplus.status.Words
import com.normplus.ui.components.BannerContent
import com.normplus.ui.components.BannerTone
import com.normplus.ui.components.PillContent
import com.normplus.ui.components.ShellStatus
import com.normplus.ui.components.StatusKind
import com.normplus.ui.components.countText
import com.normplus.ui.components.currentLocale
import com.normplus.ui.components.rememberClockFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The watch's status for every composable under the shell (#97): the same value the banner and
 * the status line are printed from. A screen's ViewModel injects `WatchStatusSource` instead.
 */
val LocalWatchStatus = compositionLocalOf { WatchStatus() }

/**
 * The separator between the status pill's parts. StatusPill reads it to TalkBack as a pause,
 * so it is exactly this.
 */
private const val STATUS_SEPARATOR = " · "

/** [Words] in the UI's language. */
@Composable
@ReadOnlyComposable
fun Words.text(): String = if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

/**
 * The status pill under every title (brief §6, #92's comment of 2026-10-09):
 * - "Norm 2 · 82% · synced 14:32" while connected, green with a tick (a bolt while charging);
 * - "Norm 2 · synced 14:32" while not, grey with the link off: the battery is only shown live.
 *   On the tabs and screens the banner stands in its place then, saying why;
 * - "Reading sport records · 412 of 922" while a sync runs, violet with the sending arc.
 * A sync on an earlier day prints its date ("synced 8 Oct"); none yet, "not synced yet".
 *
 * @param today the day "synced 14:32" is counted from (a parameter for the screenshot tests).
 */
@Composable
fun statusPill(status: WatchStatus, today: LocalDate = LocalDate.now()): PillContent {
    val sync = status.sync
    if (sync is SyncState.Running) {
        val text = when (sync.stage) {
            SyncStage.Counting -> stringResource(R.string.shell_sync_counting)
            SyncStage.Sport -> stringResource(R.string.shell_sync_sport, countText(sync.done), countText(sync.total))
            SyncStage.HeartRate -> stringResource(R.string.shell_sync_heart_rate, countText(sync.done), countText(sync.total))
            SyncStage.Sleep -> stringResource(R.string.shell_sync_sleep, countText(sync.done), countText(sync.total))
        }
        return PillContent(text, StatusKind.Syncing)
    }

    val clock = rememberClockFormatter()
    val locale = currentLocale()
    val shortDate = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    val parts = mutableListOf(stringResource(R.string.shell_watch_name))
    val battery = status.battery?.takeIf { status.isReady }
    if (battery != null) parts += stringResource(R.string.shell_status_battery, battery.percent)
    val lastSync = status.lastSyncEpochMs
    parts += if (lastSync == null) {
        stringResource(R.string.shell_status_not_synced)
    } else {
        val day = Instant.ofEpochMilli(lastSync).atZone(ZoneId.systemDefault()).toLocalDate()
        stringResource(R.string.shell_status_synced_at, if (day == today) clock(lastSync) else shortDate.format(day))
    }
    val kind = when {
        !status.isReady -> StatusKind.Offline
        battery?.charging == true -> StatusKind.Charging
        else -> StatusKind.Fine
    }
    return PillContent(parts.joinToString(STATUS_SEPARATOR), kind)
}

/**
 * Everything a header prints about the watch: the pill, and the banner that stands in its place
 * whenever StatusWords has something to say. Nothing at all before a watch is saved.
 */
@Composable
fun shellStatus(status: WatchStatus, onFix: (Fix) -> Unit, today: LocalDate = LocalDate.now()): ShellStatus {
    if (status.link == Link.NoWatch) return ShellStatus()
    return ShellStatus(pill = statusPill(status, today), banner = bannerContent(status, onFix))
}

/** The banner's content for [status] (StatusWords.banner), its fix carried out by [onFix]. */
@Composable
fun bannerContent(status: WatchStatus, onFix: (Fix) -> Unit): BannerContent? {
    val message = StatusWords.banner(status) ?: return null
    val fix = message.fix
    return BannerContent(
        message = message.text.text(),
        tone = message.severity.tone(),
        hint = message.hint?.text(),
        actionLabel = message.action?.text(),
        onAction = if (fix != null) ({ onFix(fix) }) else null,
    )
}

/** How each severity reads in the banner. */
fun Severity.tone(): BannerTone = when (this) {
    Severity.Working -> BannerTone.Working
    Severity.Notice -> BannerTone.Notice
    Severity.NeedsFixing -> BannerTone.NeedsFixing
}
