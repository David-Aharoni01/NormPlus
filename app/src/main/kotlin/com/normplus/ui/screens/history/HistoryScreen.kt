package com.normplus.ui.screens.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import com.normplus.R
import com.normplus.ui.components.BarChart
import com.normplus.ui.components.CardTitle
import com.normplus.ui.components.ChartBar
import com.normplus.ui.components.DayRow
import com.normplus.ui.components.FitText
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.PillButton
import com.normplus.ui.components.PillTone
import com.normplus.ui.components.SleepLegend
import com.normplus.ui.components.SleepNight
import com.normplus.ui.components.SleepStackChart
import com.normplus.ui.components.countText
import com.normplus.ui.components.currentLocale
import com.normplus.ui.components.dateSpokenText
import com.normplus.ui.components.durationFigure
import com.normplus.ui.components.durationText
import com.normplus.ui.components.figure
import com.normplus.ui.theme.NormPlusTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.max

/*
 * History (#100): the metric tabs and the range, the chart over the range, then one row per
 * day, newest first, grouped by month and read a page at a time as the list scrolls. Every
 * figure comes from the synced records; a day without a record has no bar and no row.
 */

/**
 * History, stateless: what [HistoryRoute] draws from [HistoryViewModel], and what the
 * screenshot tests draw from a hand-made [state].
 *
 * @param canSync the watch is connected and no sync is running: the empty state's Sync now works.
 */
@Composable
fun HistoryContent(
    state: HistoryUiState,
    contentPadding: PaddingValues,
    canSync: Boolean,
    onSelectMetric: (HistoryMetric) -> Unit,
    onSelectRange: (HistoryRange) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val spacing = NormPlusTheme.spacing
    val months = remember(state.rows) { state.rows.groupBy { YearMonth.from(it.date) }.toList() }

    // The next page is read when the last rows come into view.
    LaunchedEffect(listState, state.rows.size, state.rowsComplete) {
        if (state.rowsComplete) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = spacing.gutter,
            end = spacing.gutter,
            top = contentPadding.calculateTopPadding() + spacing.s,
            bottom = contentPadding.calculateBottomPadding() + spacing.l,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        if (state.loaded && state.nothingSynced) {
            item(key = "empty") { NothingSynced(canSync, onSync) }
            return@LazyColumn
        }
        item(key = "metrics") { MetricChips(state.metric, onSelectMetric) }
        item(key = "range") { RangeButtons(state.range, onSelectRange, Modifier.padding(bottom = spacing.s)) }
        if (state.failed) {
            item(key = "failed") {
                Notice(
                    title = stringResource(R.string.history_failed_title),
                    tone = NoticeTone.Failed,
                    body = stringResource(R.string.history_failed_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
        }
        if (!state.loaded) return@LazyColumn
        item(key = "chart") { ChartCard(state) }
        if (state.rows.isEmpty() && !state.failed) {
            item(key = "rows-empty") { NoRows(state.metric) }
        }
        months.forEach { (month, days) ->
            item(key = "m$month") { MonthHeading(month) }
            items(days, key = { it.date.toEpochDay() }) { day ->
                HistoryDayRow(day, state.metric, state.goal, state.today, onOpenDay)
            }
        }
        if (state.loadingMore) {
            item(key = "more") { LoadingMore() }
        }
    }
}

/** How many items before the end the next page is asked for. */
private const val LOAD_AHEAD = 8

private val HistoryMetric.icon: ImageVector
    get() = when (this) {
        HistoryMetric.Steps -> Icons.AutoMirrored.Rounded.DirectionsWalk
        HistoryMetric.HeartRate -> Icons.Rounded.Favorite
        HistoryMetric.Sleep -> Icons.Rounded.Bedtime
        HistoryMetric.Calories -> Icons.Rounded.LocalFireDepartment
    }

private val HistoryMetric.label: Int
    get() = when (this) {
        HistoryMetric.Steps -> R.string.history_metric_steps
        HistoryMetric.HeartRate -> R.string.history_metric_heart_rate
        HistoryMetric.Sleep -> R.string.history_metric_sleep
        HistoryMetric.Calories -> R.string.history_metric_calories
    }

private val HistoryRange.label: Int
    get() = when (this) {
        HistoryRange.Week -> R.string.history_range_week
        HistoryRange.Month -> R.string.history_range_month
        HistoryRange.ThreeMonths -> R.string.history_range_three_months
    }

/** The metric tabs: pills in a row that scrolls sideways when the words are large. */
@Composable
private fun MetricChips(selected: HistoryMetric, onSelect: (HistoryMetric) -> Unit) {
    val spacing = NormPlusTheme.spacing
    val groupLabel = stringResource(R.string.history_metrics_label)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        HistoryMetric.entries.forEach { m ->
            val isSelected = m == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(m) },
                label = { Text(stringResource(m.label), style = NormPlusTheme.type.pill, maxLines = 1) },
                leadingIcon = { Icon(m.icon, contentDescription = null, Modifier.size(spacing.smallIcon)) },
                shape = NormPlusTheme.shapes.pill,
            )
        }
    }
}

/** The range: Week · Month · 3 months, Material's segmented buttons, full width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeButtons(selected: HistoryRange, onSelect: (HistoryRange) -> Unit, modifier: Modifier = Modifier) {
    val count = HistoryRange.entries.size
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        HistoryRange.entries.forEachIndexed { i, r ->
            SegmentedButton(
                selected = r == selected,
                onClick = { onSelect(r) },
                shape = SegmentedButtonDefaults.itemShape(i, count),
                label = { Text(stringResource(r.label), style = NormPlusTheme.type.pill, maxLines = 1) },
            )
        }
    }
}

/** The chart over the range, with its line about the range above it. */
@Composable
private fun ChartCard(state: HistoryUiState) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val caption = stringResource(R.string.history_range_caption, state.range.days)
    val summary = summarise(state.metric, state.chart, state.goal)
    NormCard(Modifier.fillMaxWidth()) {
        CardTitle(caption, state.metric.icon)
        if (summary.daysWithData == 0) {
            Text(
                stringResource(rangeEmpty(state.metric)),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                modifier = Modifier.padding(top = spacing.m),
            )
            Text(
                stringResource(R.string.history_range_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            return@NormCard
        }
        val (figureText, detail) = summaryFigure(state.metric, summary)
        FitText(figureText, NormPlusTheme.type.tileFigure, Modifier.fillMaxWidth().padding(top = spacing.m), color = scheme.onSurface)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = spacing.xs))
        Spacer(Modifier.height(spacing.l))
        val spoken = chartSpoken(state.metric, caption, summary)
        val axis = axisLabels(state.range, state.today)
        when (state.metric) {
            HistoryMetric.Steps, HistoryMetric.Calories -> BarChart(
                bars = state.chart.map { d ->
                    (d?.value as? DayValue.Count)?.let { ChartBar(it.value.toFloat(), goalMet = goalMet(state.metric, d, state.goal)) }
                },
                contentDescription = spoken,
                goal = state.goal?.toFloat(),
                goalLabel = state.goal?.let { countText(it) },
                axisLabels = axis,
            )
            HistoryMetric.HeartRate -> {
                val low = summary.low ?: 0
                val high = summary.high ?: 0
                BarChart(
                    bars = state.chart.map { d ->
                        (d?.value as? DayValue.Pulse)?.let { p ->
                            ChartBar(high = p.high.toFloat(), low = p.low.toFloat(), mark = if (p.readings > 1) p.average.toFloat() else null)
                        }
                    },
                    contentDescription = spoken,
                    // A little room round the range, on round tens.
                    scaleMin = max(0, (low / 10 - 1) * 10).toFloat(),
                    scaleMax = ((high / 10 + 1) * 10).toFloat(),
                    axisLabels = axis,
                )
            }
            HistoryMetric.Sleep -> {
                SleepStackChart(
                    nights = state.chart.map { d ->
                        (d?.value as? DayValue.Night)?.let { SleepNight(it.deepMinutes, it.lightMinutes, it.awakeMinutes) }
                    },
                    contentDescription = spoken,
                    axisLabels = axis,
                )
                SleepLegend(Modifier.padding(top = spacing.m))
            }
        }
    }
}

private fun rangeEmpty(metric: HistoryMetric): Int = when (metric) {
    HistoryMetric.Steps -> R.string.history_range_empty_steps
    HistoryMetric.HeartRate -> R.string.history_range_empty_heart_rate
    HistoryMetric.Sleep -> R.string.history_range_empty_sleep
    HistoryMetric.Calories -> R.string.history_range_empty_calories
}

/** The range's figure ("7,412 steps a day") and the line under it. */
@Composable
private fun summaryFigure(metric: HistoryMetric, s: RangeSummary): Pair<AnnotatedString, String> {
    val avg = s.average ?: 0
    val days = pluralStringResource(R.plurals.history_detail_average, s.daysWithData, s.daysWithData)
    return when (metric) {
        HistoryMetric.Steps -> figure(countText(avg) to stringResource(R.string.history_unit_steps_a_day)) to
            pluralStringResource(R.plurals.history_summary_goal_days, s.goalDays, s.goalDays, s.daysWithData)
        HistoryMetric.Calories -> figure(countText(avg) to stringResource(R.string.history_unit_kcal_a_day)) to days
        HistoryMetric.HeartRate -> figure(countText(avg) to stringResource(R.string.history_unit_bpm_average)) to
            stringResource(R.string.history_detail_heart_rate, countText(s.low ?: 0), countText(s.high ?: 0))
        HistoryMetric.Sleep -> {
            val d = durationFigure(avg)
            val words = stringResource(R.string.history_unit_asleep_a_night)
            val unit = figure("" to words)
            AnnotatedString.Builder().apply { append(d); append(unit) }.toAnnotatedString() to
                pluralStringResource(R.plurals.history_detail_average_nights, s.daysWithData, s.daysWithData)
        }
    }
}

/** The chart for TalkBack, as one sentence. */
@Composable
private fun chartSpoken(metric: HistoryMetric, caption: String, s: RangeSummary): String {
    val avg = s.average ?: 0
    return when (metric) {
        HistoryMetric.Steps -> stringResource(
            R.string.history_chart_spoken_steps, caption,
            stringResource(R.string.history_summary_steps, countText(avg)) + ". " +
                pluralStringResource(R.plurals.history_summary_goal_days, s.goalDays, s.goalDays, s.daysWithData),
        )
        HistoryMetric.Calories -> stringResource(
            R.string.history_chart_spoken_calories, caption, stringResource(R.string.history_summary_calories, countText(avg)),
        )
        HistoryMetric.HeartRate -> stringResource(
            R.string.history_chart_spoken_heart_rate, caption,
            stringResource(R.string.history_summary_heart_rate, countText(avg), countText(s.low ?: 0), countText(s.high ?: 0)),
        )
        HistoryMetric.Sleep -> stringResource(
            R.string.history_chart_spoken_sleep, caption, stringResource(R.string.history_summary_sleep, durationText(avg)),
        )
    }
}

/**
 * Under the chart: a week names each day; a month or three name four dates, the first and
 * last at the ends.
 */
@Composable
private fun axisLabels(range: HistoryRange, today: LocalDate): List<String> {
    val locale = currentLocale()
    val weekday = stringResource(R.string.history_axis_weekday_pattern)
    val date = stringResource(R.string.history_axis_date_pattern)
    return remember(range, today, locale, weekday, date) {
        val first = range.firstDay(today)
        if (range == HistoryRange.Week) {
            val f = DateTimeFormatter.ofPattern(weekday, locale)
            (0 until range.days).map { f.format(first.plusDays(it.toLong())) }
        } else {
            val f = DateTimeFormatter.ofPattern(date, locale)
            val n = 4
            (0 until n).map { i -> f.format(first.plusDays(((range.days - 1) * i / (n - 1)).toLong())) }
        }
    }
}

@Composable
private fun MonthHeading(month: YearMonth) {
    val spacing = NormPlusTheme.spacing
    val locale = currentLocale()
    val pattern = stringResource(R.string.history_month_pattern)
    val text = remember(month, locale, pattern) { DateTimeFormatter.ofPattern(pattern, locale).format(month) }
    Text(
        text,
        style = NormPlusTheme.type.sectionTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = spacing.l, bottom = spacing.xs, start = spacing.xs)
            .semantics { heading() },
    )
}

/** One day: its date, its figures for the metric, "Goal reached" on a goal day. Opens the day. */
@Composable
private fun HistoryDayRow(day: HistoryDay, metric: HistoryMetric, goal: Int?, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val locale = currentLocale()
    val weekdayPattern = stringResource(R.string.history_row_weekday_pattern)
    val datePattern = stringResource(
        if (day.date.year == today.year) R.string.history_row_date_pattern else R.string.history_row_date_year_pattern,
    )
    val weekday = remember(day.date, locale, weekdayPattern) { DateTimeFormatter.ofPattern(weekdayPattern, locale).format(day.date) }
    val dateText = remember(day.date, locale, datePattern) { DateTimeFormatter.ofPattern(datePattern, locale).format(day.date) }
    val relative = when (day.date) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> null
    }
    val met = goalMet(metric, day, goal)
    val (summary, spokenFigures) = rowFigures(metric, day.value)
    val spokenDate = dateSpokenText(day.date).let { d ->
        if (relative != null) stringResource(R.string.history_row_spoken_relative, relative, d) else d
    }
    DayRow(
        weekday = weekday,
        day = day.date.dayOfMonth.toString(),
        title = relative ?: dateText,
        summary = summary,
        goalReached = met,
        spoken = stringResource(if (met) R.string.history_row_spoken_goal else R.string.history_row_spoken, spokenDate, spokenFigures),
        onClick = { onOpenDay(day.date) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A row's figures, printed and for TalkBack. */
@Composable
private fun rowFigures(metric: HistoryMetric, value: DayValue): Pair<String, String> = when (value) {
    is DayValue.Count -> {
        val text = if (metric == HistoryMetric.Calories) {
            stringResource(R.string.history_row_calories, countText(value.value))
        } else {
            pluralStringResource(R.plurals.history_row_steps, value.value, countText(value.value))
        }
        text to text
    }
    is DayValue.Pulse -> {
        val text = if (value.readings == 1) {
            stringResource(R.string.history_row_heart_rate_single, countText(value.high))
        } else {
            stringResource(R.string.history_row_heart_rate, countText(value.low), countText(value.high), countText(value.average))
        }
        text to stringResource(R.string.history_heart_rate_spoken, countText(value.low), countText(value.high), countText(value.average))
    }
    is DayValue.Night -> stringResource(R.string.history_row_sleep, durationText(value.asleepMinutes), durationText(value.deepMinutes)) to
        stringResource(
            R.string.history_sleep_spoken,
            durationText(value.asleepMinutes), durationText(value.deepMinutes), durationText(value.lightMinutes), durationText(value.awakeMinutes),
        )
}

@Composable
private fun NoRows(metric: HistoryMetric) {
    val spacing = NormPlusTheme.spacing
    val words = when (metric) {
        HistoryMetric.Steps -> R.string.history_rows_empty_steps
        HistoryMetric.HeartRate -> R.string.history_rows_empty_heart_rate
        HistoryMetric.Sleep -> R.string.history_rows_empty_sleep
        HistoryMetric.Calories -> R.string.history_rows_empty_calories
    }
    Text(
        stringResource(words),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.l),
    )
}

/** Nothing has been synced: say where the days come from, and offer the sync. */
@Composable
private fun NothingSynced(canSync: Boolean, onSync: () -> Unit) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(spacing.xl)) {
        Icon(Icons.Rounded.History, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(spacing.icon))
        Text(
            stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = spacing.l).semantics { heading() },
        )
        Text(
            stringResource(R.string.history_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s),
        )
        Row(Modifier.padding(top = spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = stringResource(R.string.history_empty_action),
                onClick = onSync,
                tone = PillTone.Primary,
                icon = Icons.Rounded.Sync,
                enabled = canSync,
            )
        }
        if (!canSync) {
            Text(
                stringResource(R.string.history_empty_needs_watch),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
    }
}

@Composable
private fun LoadingMore() {
    val spacing = NormPlusTheme.spacing
    val words = stringResource(R.string.history_loading_more)
    Box(Modifier.fillMaxWidth().padding(vertical = spacing.l), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(spacing.icon).semantics { contentDescription = words },
            strokeWidth = spacing.xxs,
        )
    }
}
