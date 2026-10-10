package com.normplus.ui.screens.daydetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.R
import com.normplus.ui.components.BarChart
import com.normplus.ui.components.CardTitle
import com.normplus.ui.components.ChartBar
import com.normplus.ui.components.FitText
import com.normplus.ui.components.HeroMetricCard
import com.normplus.ui.components.MetricGrid
import com.normplus.ui.components.MetricTile
import com.normplus.ui.components.NormCard
import com.normplus.ui.components.Notice
import com.normplus.ui.components.NoticeTone
import com.normplus.ui.components.ScreenScaffold
import com.normplus.ui.components.SleepLegend
import com.normplus.ui.components.SleepStageBand
import com.normplus.ui.components.countText
import com.normplus.ui.components.currentLocale
import com.normplus.ui.components.dateLineText
import com.normplus.ui.components.durationFigure
import com.normplus.ui.components.durationText
import com.normplus.ui.components.figure
import com.normplus.ui.components.rememberClockFormatter
import com.normplus.ui.theme.NormMotion
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/** Metres in a mile, for distances shown in imperial units. */
private const val METRES_PER_MILE = 1609.344

/**
 * Day detail (#101), stateless: one day's hero card and tiles, the same anatomy as Today
 * without the live status, then its steps by the half hour, its heart-rate readings and the
 * night's sleep as a stage band. Previous and Next sit in the header; a swipe does the same,
 * and the days move along Material's shared axis X (a cut with Remove animations).
 *
 * @param lastSyncEpochMs the last sync, for today's "so far" line.
 * @param clock prints a time of day (a parameter so the screenshot tests print a fixed one).
 * @param zone the zone the day's hours are in: the phone's.
 */
@Composable
fun DayDetailContent(
    state: DayDetailUiState,
    lastSyncEpochMs: Long?,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    clock: (Long) -> String = rememberClockFormatter(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    ScreenScaffold(
        title = state.date?.let { titleText(it, state.today) } ?: stringResource(R.string.daydetail_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = onPrevious, enabled = state.canGoPrevious) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = stringResource(R.string.daydetail_previous))
            }
            IconButton(onClick = onNext, enabled = state.canGoNext) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = stringResource(R.string.daydetail_next))
            }
        },
    ) { padding ->
        val animationsRemoved = NormPlusTheme.animationsRemoved
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        AnimatedContent(
            targetState = state,
            contentKey = { it.date },
            transitionSpec = {
                val forward = (targetState.date ?: state.today) > (initialState.date ?: state.today)
                NormMotion.sharedAxisX(forward, animationsRemoved, density, direction)
            },
            modifier = Modifier
                .fillMaxSize()
                .swipeBetweenDays(state.canGoPrevious, state.canGoNext, onPrevious, onNext),
            label = "day",
        ) { s ->
            DayPage(s, padding, lastSyncEpochMs, onRetry, clock, zone)
        }
    }
}

/**
 * A finished horizontal swipe moves a day ([swipeDirection]); vertical scrolling is left to
 * the list, which claims a vertical drag before this sees a horizontal one.
 */
@Composable
private fun Modifier.swipeBetweenDays(canPrevious: Boolean, canNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val threshold = with(LocalDensity.current) { NormPlusTheme.spacing.touchTarget.toPx() }
    val previous by rememberUpdatedState(if (canPrevious) onPrevious else null)
    val next by rememberUpdatedState(if (canNext) onNext else null)
    var dragged by remember { mutableFloatStateOf(0f) }
    return pointerInput(rtl, threshold) {
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = {
                when (swipeDirection(dragged, threshold, rtl)) {
                    -1 -> previous?.invoke()
                    1 -> next?.invoke()
                }
            },
            onDragCancel = { dragged = 0f },
            onHorizontalDrag = { change, amount ->
                dragged += amount
                change.consume()
            },
        )
    }
}

@Composable
private fun DayPage(
    state: DayDetailUiState,
    padding: PaddingValues,
    lastSyncEpochMs: Long?,
    onRetry: () -> Unit,
    clock: (Long) -> String,
    zone: ZoneId,
) {
    val spacing = NormPlusTheme.spacing
    val date = state.date
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(
            start = spacing.gutter,
            end = spacing.gutter,
            top = padding.calculateTopPadding() + spacing.s,
            bottom = padding.calculateBottomPadding() + spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.l),
    ) {
        if (date == null || !state.loaded) return@LazyColumn
        item(key = "date") { DateLine(date, state.today) }
        if (state.failed) {
            item(key = "failed") {
                Notice(
                    title = stringResource(R.string.daydetail_failed_title),
                    tone = NoticeTone.Failed,
                    body = stringResource(R.string.daydetail_failed_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
        }
        val day = state.day ?: return@LazyColumn
        item(key = "hero") { Hero(day, state, lastSyncEpochMs, clock) }
        if (day.isEmpty) {
            item(key = "empty") { EmptyDay() }
            return@LazyColumn
        }
        item(key = "tiles") {
            MetricGrid {
                SleepTile(day, clock)
                HeartRateTile(day, clock)
                CaloriesTile(day.calories)
                DistanceTile(day.distanceMeters, day.activeMinutes, state.imperial)
            }
        }
        item(key = "steps") { StepsCard(day, clock, zone) }
        item(key = "heart") { HeartCard(day, clock, zone) }
        item(key = "sleep") { SleepCard(day, clock) }
    }
}

/** The header's title: Today, Yesterday, or the short date (with its year when not this year's). */
@Composable
private fun titleText(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.daydetail_today)
    today.minusDays(1) -> stringResource(R.string.daydetail_yesterday)
    else -> {
        val locale = currentLocale()
        val pattern = stringResource(
            if (date.year == today.year) R.string.daydetail_title_pattern else R.string.daydetail_title_year_pattern,
        )
        remember(date, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(date) }
    }
}

/** The full date under the title: "Wednesday · 8 October", with the year when not this year's. */
@Composable
private fun DateLine(date: LocalDate, today: LocalDate) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val text = if (date.year == today.year) {
        dateLineText(date)
    } else {
        val locale = currentLocale()
        val pattern = stringResource(R.string.daydetail_date_year_pattern)
        remember(date, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(date) }
    }
    Row(Modifier.padding(horizontal = spacing.xs).semantics(mergeDescendants = true) { heading() }) {
        Icon(
            Icons.Rounded.CalendarToday,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.padding(end = spacing.s).size(spacing.smallIcon),
        )
        Text(text, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
    }
}

/** The day's steps against the goal. A day that is over says how far short it fell; today still has some to go. */
@Composable
private fun Hero(day: DayRecords, state: DayDetailUiState, lastSyncEpochMs: Long?, clock: (Long) -> String) {
    val steps = day.steps
    val goal = state.goal
    val reached = steps != null && goal > 0 && steps >= goal
    val stepsText = steps?.let { countText(it) }
    val goalText = countText(goal)
    val supporting = when {
        steps == null || reached -> stringResource(R.string.figure_of_goal, goalText)
        state.isToday -> stringResource(R.string.figure_of_goal_to_go, goalText, countText(goal - steps))
        else -> stringResource(R.string.daydetail_of_goal_short, goalText, countText(goal - steps))
    }
    val note = if (state.isToday) {
        lastSyncEpochMs?.let { stringResource(R.string.daydetail_so_far, clock(it)) }
            ?: stringResource(R.string.daydetail_so_far_none)
    } else {
        null
    }
    val spoken = when {
        stepsText == null -> stringResource(R.string.daydetail_spoken, stringResource(R.string.metric_steps), stringResource(R.string.daydetail_not_synced))
        reached -> stringResource(R.string.figure_spoken_goal_reached, stepsText, goalText)
        else -> stringResource(R.string.figure_spoken, stepsText, goalText)
    } + (note?.let { ". $it" } ?: "")
    HeroMetricCard(
        label = stringResource(R.string.metric_steps),
        icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
        figure = stepsText,
        supporting = supporting,
        progress = if (steps == null || goal <= 0) null else steps.toFloat() / goal,
        goalReached = reached,
        spoken = spoken,
        modifier = Modifier.fillMaxWidth().heroGlow(),
        note = note,
    )
}

@Composable
private fun EmptyDay() {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(spacing.xl)) {
        Text(
            stringResource(R.string.daydetail_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.daydetail_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s),
        )
    }
}

// The tiles: Today's four (#99), from the day's records.

@Composable
private fun SleepTile(day: DayRecords, clock: (Long) -> String) {
    val label = stringResource(R.string.daydetail_sleep)
    val night = day.night
    if (night == null) {
        val absent = stringResource(R.string.daydetail_sleep_absent)
        MetricTile(label, Icons.Rounded.Bedtime, null, stringResource(R.string.daydetail_spoken, label, absent), absentText = absent)
        return
    }
    val deep = stringResource(R.string.daydetail_sleep_deep, durationText(night.deepMinutes))
    // One session: its times. A nap as well: how many, and the card below gives each its times.
    val span = if (night.sessions > 1) pluralStringResource(R.plurals.daydetail_sessions, night.sessions, night.sessions)
    else stringResource(R.string.daydetail_span, clock(night.startMillis), clock(night.endMillis))
    val spokenSpan = if (night.sessions > 1) span
    else stringResource(R.string.daydetail_spoken_span, clock(night.startMillis), clock(night.endMillis))
    MetricTile(
        label = label,
        icon = Icons.Rounded.Bedtime,
        value = durationFigure(night.asleepMinutes),
        spoken = stringResource(
            R.string.daydetail_spoken_with_detail, label,
            stringResource(R.string.daydetail_spoken_asleep, durationText(night.asleepMinutes)), "$deep, $spokenSpan",
        ),
        detail = stringResource(R.string.daydetail_detail_join, deep, span),
    )
}

@Composable
private fun HeartRateTile(day: DayRecords, clock: (Long) -> String) {
    val label = stringResource(R.string.daydetail_heart_rate)
    val average = day.heartAverage
    if (average == null) {
        val absent = stringResource(R.string.daydetail_heart_rate_absent)
        MetricTile(label, Icons.Rounded.Favorite, null, stringResource(R.string.daydetail_spoken, label, absent), absentText = absent)
        return
    }
    val unit = stringResource(R.string.daydetail_unit_bpm)
    val low = day.heartLow ?: average
    val high = day.heartHigh ?: average
    val detail: String
    val said: String
    if (day.heart.size == 1) {
        detail = stringResource(R.string.daydetail_heart_rate_at, clock(day.heart.first().atMillis))
        said = detail
    } else {
        detail = stringResource(
            R.string.daydetail_detail_join,
            stringResource(R.string.daydetail_heart_rate_average),
            stringResource(R.string.daydetail_heart_rate_range, countText(low), countText(high)),
        )
        said = stringResource(R.string.daydetail_spoken_heart_average, countText(average), countText(low), countText(high))
    }
    MetricTile(
        label = label,
        icon = Icons.Rounded.Favorite,
        value = figure(countText(average) to unit),
        spoken = stringResource(R.string.daydetail_spoken_with_detail, label, "${countText(average)} $unit", said),
        detail = detail,
    )
}

@Composable
private fun CaloriesTile(calories: Int?) {
    val label = stringResource(R.string.daydetail_calories)
    if (calories == null) {
        val absent = stringResource(R.string.daydetail_not_synced)
        MetricTile(label, Icons.Rounded.LocalFireDepartment, null, stringResource(R.string.daydetail_spoken, label, absent), absentText = absent)
        return
    }
    val unit = stringResource(R.string.daydetail_unit_kcal)
    val kcal = countText(calories)
    MetricTile(label, Icons.Rounded.LocalFireDepartment, figure(kcal to unit), stringResource(R.string.daydetail_spoken, label, "$kcal $unit"))
}

@Composable
private fun DistanceTile(meters: Int?, activeMinutes: Int?, imperial: Boolean) {
    val label = stringResource(R.string.daydetail_distance)
    val active = activeMinutes?.let { stringResource(R.string.daydetail_active, durationText(it)) }
    val locale = currentLocale()
    if (meters == null) {
        val absent = stringResource(R.string.daydetail_not_synced)
        MetricTile(label, Icons.Rounded.Route, null, stringResource(R.string.daydetail_spoken, label, absent), absentText = absent)
        return
    }
    val unit = stringResource(if (imperial) R.string.daydetail_unit_mi else R.string.daydetail_unit_km)
    val amount = if (imperial) meters / METRES_PER_MILE else meters / 1000.0
    val text = remember(amount, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(amount)
    }
    MetricTile(
        label = label,
        icon = Icons.Rounded.Route,
        value = figure(text to unit),
        spoken = if (active == null) stringResource(R.string.daydetail_spoken, label, "$text $unit")
        else stringResource(R.string.daydetail_spoken_with_detail, label, "$text $unit", active),
        detail = active,
    )
}

// Below the tiles: the day as its records came.

/** The day's hours under a chart: midnight, 6, noon, 18, midnight, as the phone prints times. */
@Composable
private fun hourLabels(date: LocalDate, clock: (Long) -> String, zone: ZoneId): List<String> =
    remember(date, clock, zone) { (0..4).map { k -> clock(instantOf(date, k * 6 * 60, zone)) } }

/** The epoch milliseconds of [minutes] past [date]'s midnight (24 h past it is the next midnight). */
private fun instantOf(date: LocalDate, minutes: Int, zone: ZoneId): Long =
    date.plusDays((minutes / (24 * 60)).toLong())
        .atTime(LocalTime.MIDNIGHT.plusMinutes((minutes % (24 * 60)).toLong()))
        .atZone(zone).toInstant().toEpochMilli()

@Composable
internal fun StepsCard(day: DayRecords, clock: (Long) -> String, zone: ZoneId) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val title = stringResource(R.string.daydetail_steps_chart)
    NormCard(Modifier.fillMaxWidth()) {
        CardTitle(title, Icons.AutoMirrored.Rounded.DirectionsWalk, Modifier.semantics { heading() })
        val total = day.steps
        if (total == null) {
            Absent(stringResource(R.string.daydetail_steps_chart_empty))
            return@NormCard
        }
        val stepsWords = pluralStringResource(R.plurals.daydetail_steps, total, countText(total))
        val busiest = day.busiestHalfHour
        val busiestSteps = busiest?.let { day.halfHours[it] ?: 0 }
        val busiestText = busiest?.let {
            val span = stringResource(R.string.daydetail_span, clock(instantOf(day.date, it * 30, zone)), clock(instantOf(day.date, it * 30 + 30, zone)))
            span to pluralStringResource(R.plurals.daydetail_steps, busiestSteps ?: 0, countText(busiestSteps ?: 0))
        }
        val recorded = day.halfHours.count { it != null }
        // The figure is the busiest half hour: the day's total is the hero's.
        if (busiestSteps != null) {
            FitText(
                figure(countText(busiestSteps) to stringResource(R.string.daydetail_unit_steps)),
                NormPlusTheme.type.tileFigure,
                Modifier.fillMaxWidth().padding(top = spacing.m),
                color = scheme.onSurface,
            )
        }
        Text(
            busiestText?.let { (span, _) -> stringResource(R.string.daydetail_busiest, span, pluralStringResource(R.plurals.daydetail_half_hours, recorded, recorded)) }
                ?: pluralStringResource(R.plurals.daydetail_half_hours, recorded, recorded),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = if (busiestSteps != null) spacing.xs else spacing.m),
        )
        Spacer(Modifier.height(spacing.l))
        BarChart(
            bars = day.halfHours.map { it?.let { steps -> ChartBar(steps.toFloat()) } },
            contentDescription = busiestText?.let { (span, steps) -> stringResource(R.string.daydetail_steps_chart_spoken, stepsWords, span, steps) }
                ?: stringResource(R.string.daydetail_steps_chart_spoken_still, stepsWords),
            axisLabels = hourLabels(day.date, clock, zone),
        )
    }
}

@Composable
internal fun HeartCard(day: DayRecords, clock: (Long) -> String, zone: ZoneId) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(Modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.daydetail_heart_chart), Icons.Rounded.Favorite, Modifier.semantics { heading() })
        val average = day.heartAverage
        val low = day.heartLow
        val high = day.heartHigh
        if (average == null || low == null || high == null) {
            Absent(stringResource(R.string.daydetail_heart_chart_empty))
            return@NormCard
        }
        val unit = stringResource(R.string.daydetail_unit_bpm)
        val readings = pluralStringResource(R.plurals.daydetail_readings, day.heart.size, day.heart.size)
        val rangeText = if (low == high) countText(low) else stringResource(R.string.daydetail_span, countText(low), countText(high))
        FitText(figure(rangeText to unit), NormPlusTheme.type.tileFigure, Modifier.fillMaxWidth().padding(top = spacing.m), color = scheme.onSurface)
        Text(
            stringResource(R.string.daydetail_heart_summary, readings, countText(average)),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
        Spacer(Modifier.height(spacing.l))
        val builder = remember(zone) { DayBuilder(zone) }
        val slots = remember(day.heart, builder) { builder.heartSlots(day.heart) }
        BarChart(
            bars = slots.map { r -> r?.let { ChartBar(high = it.last.toFloat(), low = it.first.toFloat()) } },
            contentDescription = stringResource(R.string.daydetail_heart_chart_spoken, readings, countText(low), countText(high), countText(average)),
            // A little room round the readings, on round tens.
            scaleMin = max(0, (low / 10 - 1) * 10).toFloat(),
            scaleMax = ((high / 10 + 1) * 10).toFloat(),
            axisLabels = hourLabels(day.date, clock, zone),
        )
    }
}

@Composable
internal fun SleepCard(day: DayRecords, clock: (Long) -> String) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    NormCard(Modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.daydetail_sleep_card), Icons.Rounded.Bedtime, Modifier.semantics { heading() })
        val night = day.night
        if (night == null || day.sleep.isEmpty()) {
            Absent(stringResource(R.string.daydetail_sleep_card_empty))
            return@NormCard
        }
        // The night's figures, read as one sentence.
        val spokenSpans = day.sleep.map { stringResource(R.string.daydetail_spoken_span, clock(it.startMillis), clock(it.endMillis)) }
        val spoken = stringResource(
            R.string.daydetail_sleep_card_spoken,
            durationText(night.asleepMinutes), spokenSpans.joinToString(", "),
            durationText(night.deepMinutes), durationText(night.lightMinutes), durationText(night.awakeMinutes),
        )
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            val asleep = AnnotatedString.Builder().apply {
                append(durationFigure(night.asleepMinutes))
                append(figure("" to stringResource(R.string.daydetail_asleep)))
            }.toAnnotatedString()
            FitText(asleep, NormPlusTheme.type.tileFigure, Modifier.fillMaxWidth().padding(top = spacing.m), color = scheme.onSurface)
            val spans = day.sleep.map { stringResource(R.string.daydetail_span, clock(it.startMillis), clock(it.endMillis)) }
            Text(
                spans.joinToString(stringResource(R.string.daydetail_sessions_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            Row(Modifier.fillMaxWidth().padding(top = spacing.l), horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
                StageFigure(stringResource(R.string.daydetail_stage_deep), night.deepMinutes, Modifier.weight(1f))
                StageFigure(stringResource(R.string.daydetail_stage_light), night.lightMinutes, Modifier.weight(1f))
                StageFigure(stringResource(R.string.daydetail_stage_awake), night.awakeMinutes, Modifier.weight(1f))
            }
        }
        day.sleep.forEach { session ->
            val s = clock(session.startMillis)
            val e = clock(session.endMillis)
            SleepStageBand(
                spans = session.spans,
                startMillis = session.startMillis,
                endMillis = session.endMillis,
                contentDescription = stringResource(
                    R.string.daydetail_band_spoken, s, e, durationText(session.asleepMinutes),
                    durationText(session.deepMinutes), durationText(session.lightMinutes), durationText(session.awakeMinutes),
                ),
                modifier = Modifier.padding(top = spacing.l),
                startLabel = s,
                endLabel = e,
            )
        }
        SleepLegend(Modifier.padding(top = spacing.m))
        Text(
            stringResource(R.string.daydetail_asleep_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.s),
        )
    }
}

/** One stage's time in the night: its name, quiet, over the duration. */
@Composable
private fun StageFigure(label: String, minutes: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
        FitText(durationFigure(minutes), MaterialTheme.typography.titleLarge, Modifier.fillMaxWidth(), color = scheme.onSurface)
    }
}

/** What a card says when the day has no record of its kind. */
@Composable
private fun Absent(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = NormPlusTheme.spacing.m),
    )
}
