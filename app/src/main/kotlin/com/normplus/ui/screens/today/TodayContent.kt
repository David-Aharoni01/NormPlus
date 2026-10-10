package com.normplus.ui.screens.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.normplus.R
import com.normplus.ui.components.DayRow
import com.normplus.ui.components.MetricGrid
import com.normplus.ui.components.MetricTile
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.StepsHeroCard
import com.normplus.ui.components.countText
import com.normplus.ui.components.currentLocale
import com.normplus.ui.components.dateSpokenText
import com.normplus.ui.components.durationFigure
import com.normplus.ui.components.durationText
import com.normplus.ui.components.figure
import com.normplus.ui.components.rememberClockFormatter
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Metres in a mile, for distances shown in imperial units. */
private const val METRES_PER_MILE = 1609.344

/**
 * Today (#99), stateless: the steps hero against the goal, a sync that stopped short, the
 * two-column tiles (sleep, heart rate, calories, distance with active minutes) and the
 * Yesterday row. It scrolls under the shell's header and capsule ([contentPadding]); pulling
 * it down syncs.
 *
 * @param clock prints a time of day (a parameter so the screenshot tests print a fixed one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayContent(
    state: TodayUiState,
    contentPadding: PaddingValues,
    onSync: () -> Unit,
    onDismissShortfall: (Long) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    clock: (Long) -> String = rememberClockFormatter(),
) {
    val spacing = NormPlusTheme.spacing
    val pull = rememberPullToRefreshState()
    val top = contentPadding.calculateTopPadding()
    PullToRefreshBox(
        isRefreshing = state.syncing,
        onRefresh = { if (state.canSync) onSync() },
        state = pull,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = state.syncing,
                containerColor = NormPlusTheme.colors.raised,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
            )
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.gutter,
                end = spacing.gutter,
                top = top + spacing.s,
                bottom = contentPadding.calculateBottomPadding() + spacing.l,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.l),
        ) {
            state.shortfall?.let { short ->
                item(key = "shortfall") { Shortfall(short, state.canSync, onSync, onDismissShortfall) }
            }
            item(key = "steps") {
                val note = when {
                    state.asOfEpochMs != null -> stringResource(R.string.today_as_of, clock(state.asOfEpochMs))
                    state.nothingSynced -> stringResource(R.string.today_nothing_yet)
                    else -> null
                }
                StepsHeroCard(
                    steps = state.steps,
                    goal = state.goal,
                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                    modifier = Modifier.fillMaxWidth().heroGlow(),
                    note = note,
                )
            }
            item(key = "tiles") {
                MetricGrid {
                    SleepTile(state.sleep, clock)
                    HeartRateTile(state.heartRate, clock)
                    CaloriesTile(state.calories)
                    DistanceTile(state.distanceMeters, state.activeMinutes, state.imperial)
                }
            }
            item(key = "yesterday") { YesterdayRow(state.yesterday, state.goal, onOpenDay) }
        }
    }
}

@Composable
private fun Shortfall(short: SyncShortfall, canSync: Boolean, onSync: () -> Unit, onDismiss: (Long) -> Unit) {
    val body = if (short.received != null && short.expected != null) {
        stringResource(R.string.today_short_counted, countText(short.received), countText(short.expected))
    } else {
        stringResource(R.string.today_short_body)
    }
    Notice(
        title = stringResource(R.string.today_short_title),
        tone = NoticeTone.Failed,
        body = body,
        actionLabel = if (canSync) stringResource(R.string.action_retry) else null,
        onAction = if (canSync) onSync else null,
        onDismiss = { onDismiss(short.atEpochMs) },
    )
}

@Composable
private fun SleepTile(sleep: SleepSummary?, clock: (Long) -> String) {
    val label = stringResource(R.string.today_sleep)
    if (sleep == null) {
        val absent = stringResource(R.string.today_sleep_absent)
        MetricTile(label, Icons.Rounded.Bedtime, null, stringResource(R.string.today_spoken, label, absent), absentText = absent)
        return
    }
    val deep = sleep.deepMinutes?.let { stringResource(R.string.today_sleep_deep, durationText(it)) }
    val span = if (sleep.startEpochMs != null && sleep.endEpochMs != null) {
        stringResource(R.string.today_sleep_span, clock(sleep.startEpochMs), clock(sleep.endEpochMs))
    } else {
        null
    }
    val detail = joinDetail(deep, span)
    val spokenSpan = if (sleep.startEpochMs != null && sleep.endEpochMs != null) {
        stringResource(R.string.today_sleep_spoken_span, clock(sleep.startEpochMs), clock(sleep.endEpochMs))
    } else {
        null
    }
    val asleep = stringResource(R.string.today_sleep_spoken_asleep, durationText(sleep.asleepMinutes))
    MetricTile(
        label = label,
        icon = Icons.Rounded.Bedtime,
        value = durationFigure(sleep.asleepMinutes),
        spoken = spoken(label, asleep, listOfNotNull(deep, spokenSpan).joinToString(", ").ifEmpty { null }),
        detail = detail,
    )
}

@Composable
private fun HeartRateTile(reading: HeartReading?, clock: (Long) -> String) {
    val label = stringResource(R.string.today_heart_rate)
    if (reading == null) {
        val absent = stringResource(R.string.today_heart_rate_absent)
        MetricTile(label, Icons.Rounded.Favorite, null, stringResource(R.string.today_spoken, label, absent), absentText = absent)
        return
    }
    val unit = stringResource(R.string.today_unit_bpm)
    val bpm = countText(reading.bpm)
    val detail = reading.atEpochMs?.let { stringResource(R.string.today_heart_rate_at, clock(it)) }
        ?: stringResource(R.string.today_heart_rate_on_watch)
    MetricTile(
        label = label,
        icon = Icons.Rounded.Favorite,
        value = figure(bpm to unit),
        spoken = spoken(label, "$bpm $unit", detail),
        detail = detail,
    )
}

@Composable
private fun CaloriesTile(calories: Int?) {
    val label = stringResource(R.string.today_calories)
    if (calories == null) {
        val absent = stringResource(R.string.today_not_reported)
        MetricTile(label, Icons.Rounded.LocalFireDepartment, null, stringResource(R.string.today_spoken, label, absent), absentText = absent)
        return
    }
    val unit = stringResource(R.string.today_unit_kcal)
    val kcal = countText(calories)
    MetricTile(label, Icons.Rounded.LocalFireDepartment, figure(kcal to unit), spoken(label, "$kcal $unit", null))
}

@Composable
private fun DistanceTile(meters: Int?, activeMinutes: Int?, imperial: Boolean) {
    val label = stringResource(R.string.today_distance)
    val active = activeMinutes?.let { stringResource(R.string.today_active, durationText(it)) }
    val locale = currentLocale()
    val value: AnnotatedString?
    val said: String
    if (meters == null) {
        value = null
        said = stringResource(R.string.today_not_reported)
    } else {
        val unit = stringResource(if (imperial) R.string.today_unit_mi else R.string.today_unit_km)
        val amount = if (imperial) meters / METRES_PER_MILE else meters / 1000.0
        val text = remember(amount, locale) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(amount)
        }
        value = figure(text to unit)
        said = "$text $unit"
    }
    MetricTile(
        label = label,
        icon = Icons.Rounded.Route,
        value = value,
        spoken = spoken(label, said, active),
        detail = active,
        absentText = if (value == null) said else null,
    )
}

@Composable
private fun YesterdayRow(day: DaySteps, goal: Int, onOpenDay: (LocalDate) -> Unit) {
    val locale = currentLocale()
    val pattern = stringResource(R.string.today_weekday_pattern)
    val weekday = remember(day.date, locale, pattern) { DateTimeFormatter.ofPattern(pattern, locale).format(day.date) }
    val reached = day.steps != null && goal > 0 && day.steps >= goal
    val summary = day.steps?.let { pluralStringResource(R.plurals.today_steps, it, countText(it)) }
        ?: stringResource(R.string.today_yesterday_none)
    val date = dateSpokenText(day.date)
    DayRow(
        weekday = weekday,
        day = countText(day.date.dayOfMonth),
        title = stringResource(R.string.today_yesterday),
        summary = summary,
        goalReached = reached,
        spoken = stringResource(if (reached) R.string.today_yesterday_spoken_goal else R.string.today_yesterday_spoken, date, summary),
        onClick = { onOpenDay(day.date) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun joinDetail(first: String?, second: String?): String? = when {
    first != null && second != null -> stringResource(R.string.today_detail_join, first, second)
    else -> first ?: second
}

@Composable
private fun spoken(label: String, value: String, detail: String?): String =
    if (detail == null) stringResource(R.string.today_spoken, label, value)
    else stringResource(R.string.today_spoken_with_detail, label, value, detail)
