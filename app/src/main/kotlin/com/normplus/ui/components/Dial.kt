package com.normplus.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.normplus.R
import com.normplus.protocol.commands.AppSettingCommand
import com.normplus.ui.theme.NormMotion
import com.normplus.ui.theme.NormPlusTheme
import com.normplus.ui.theme.heroGlow
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/*
 * The signature move: the watch, drawn live (#92, #96). A round black display in its bezel,
 * a violet ring and the screen's one glow round it, the two physical hands where they really
 * are (the Norm 2 has an hour and a minute hand, no second hand, over a 360 x 360 round
 * AMOLED), and a pager through the watch's own screens in their order.
 *
 * Every preview is OUR drawing: an icon and a short label on the black round. Never the
 * watch's own artwork, which is the vendor's (the public-repo rule).
 */

/**
 * The screens the watch shows, as Norm+ draws them. [Face] is the watch face, always first;
 * the rest are the pages `AppSettingCommand` orders (0x01-0x0A, from AppSetting.smali and
 * BluetoothCommandConstant.smali's APP_SETTING_PAGE_TYPE_* constants).
 */
enum class WatchScreen(@StringRes val label: Int, val icon: ImageVector?, val pageType: Int?) {
    Face(R.string.watch_screen_face, null, null),
    Activity(R.string.watch_screen_activity, Icons.AutoMirrored.Rounded.DirectionsWalk, AppSettingCommand.TYPE_ACTIVITY),
    Alarm(R.string.watch_screen_alarm, Icons.Rounded.Alarm, AppSettingCommand.TYPE_ALARM),
    HeartRate(R.string.watch_screen_heart_rate, Icons.Rounded.Favorite, AppSettingCommand.TYPE_HEART_RATE),
    Music(R.string.watch_screen_music, Icons.Rounded.MusicNote, AppSettingCommand.TYPE_MUSIC),
    Reminders(R.string.watch_screen_reminders, Icons.AutoMirrored.Rounded.EventNote, AppSettingCommand.TYPE_REMINDERS),
    Sleep(R.string.watch_screen_sleep, Icons.Rounded.Bedtime, AppSettingCommand.TYPE_SLEEP),
    Stopwatch(R.string.watch_screen_stopwatch, Icons.Rounded.Timer, AppSettingCommand.TYPE_STOP_WATCH),
    Timer(R.string.watch_screen_timer, Icons.Rounded.HourglassBottom, AppSettingCommand.TYPE_TIMER),
    Weather(R.string.watch_screen_weather, Icons.Rounded.WbSunny, AppSettingCommand.TYPE_WEATHER),
    Workouts(R.string.watch_screen_workouts, Icons.Rounded.FitnessCenter, AppSettingCommand.TYPE_WORKOUTS),
    ;

    companion object {
        /** The page a type byte of `AppSettingCommand` names, or null for one Norm+ does not know. */
        fun fromPageType(type: Int): WatchScreen? = entries.firstOrNull { it.pageType == type }

        /** The watch's screens in its own order: the face, then the pages as the watch lists them. */
        fun inWatchOrder(pageOrder: List<Int>): List<WatchScreen> = listOf(Face) + pageOrder.mapNotNull(::fromPageType)
    }
}

/** One of the watch's two physical hands. */
enum class DialHand { Hour, Minute }

/**
 * The watch, drawn: the violet ring, the bezel, the black display showing [screen], and the
 * hands at [time]. It fills the square it is given.
 *
 * The hands sweep to a new time ([NormMotion.handSweep], a cut with animations removed), the
 * shortest way round, as the steppers do.
 *
 * @param date the face's date line; none when null.
 * @param showHands whether the physical hands are drawn: on the face by default. They are
 *   physical, so where they stand over the other pages is the watch's to say (#102).
 * @param highlight hands calibration (#105): the hand that is moving, in violet; the other
 *   fades back.
 * @param showTwelveMark a violet mark at twelve, where calibration asks the hand to point.
 * @param ring false for a small thumbnail, which draws the bezel without the violet ring.
 */
@Composable
fun WatchDial(
    time: LocalTime,
    modifier: Modifier = Modifier,
    screen: WatchScreen = WatchScreen.Face,
    date: LocalDate? = null,
    showHands: Boolean = screen == WatchScreen.Face,
    highlight: DialHand? = null,
    showTwelveMark: Boolean = false,
    ring: Boolean = true,
) {
    val c = NormPlusTheme.colors
    val minuteTarget = time.minute * 6f + time.second / 10f
    val hourTarget = (time.hour % 12) * 30f + time.minute / 2f
    val minute = rememberSweptAngle(minuteTarget)
    val hour = rememberSweptAngle(hourTarget)
    val spoken = dialDescription(screen, time, date)
    BoxWithConstraints(
        modifier.aspectRatio(1f).clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        val d = minOf(maxWidth, maxHeight)
        val g = DialGeometry(d, ring)
        Canvas(Modifier.fillMaxSize()) { drawCase(g, c.dialRing, c.dialBezel, c.dialBezelEdge, c.dialDisplay, ring) }
        DisplayContent(screen, date, g)
        Canvas(Modifier.fillMaxSize()) {
            if (showTwelveMark) drawTwelveMark(g, c.dialAccent)
            if (showHands) {
                val dim = c.dialHand.copy(alpha = DIMMED_HAND)
                val hourInk = when (highlight) { null -> c.dialHand; DialHand.Hour -> c.dialAccent; DialHand.Minute -> dim }
                val minuteInk = when (highlight) { null -> c.dialHand; DialHand.Minute -> c.dialAccent; DialHand.Hour -> dim }
                // The moving hand is drawn last, over the other.
                if (highlight == DialHand.Hour) {
                    drawHand(g, minute, minuteInk, DialHand.Minute)
                    drawHand(g, hour, hourInk, DialHand.Hour)
                } else {
                    drawHand(g, hour, hourInk, DialHand.Hour)
                    drawHand(g, minute, minuteInk, DialHand.Minute)
                }
                drawPivot(g, c.dialHand, c.dialAccent)
            }
        }
    }
}

private const val DIMMED_HAND = 0.32f

/** The dial's proportions, from its diameter. */
private class DialGeometry(val diameter: Dp, ring: Boolean) {
    val ringWidth = diameter * 0.012f
    val bezelOuter = diameter * (if (ring) 0.455f else 0.5f)   // radius
    val bezelWidth = diameter * (if (ring) 0.055f else 0.07f)
    val displayRadius = bezelOuter - bezelWidth
}

private fun DrawScope.drawCase(g: DialGeometry, ringInk: Color, bezel: Color, bezelEdge: Color, display: Color, ring: Boolean) {
    val centre = center
    if (ring) {
        val w = max(g.ringWidth.toPx(), 1.5.dp.toPx())
        drawCircle(ringInk, radius = size.minDimension / 2f - w / 2f, center = centre, style = Stroke(w))
    }
    drawCircle(bezel, radius = g.bezelOuter.toPx(), center = centre)
    // A lighter edge outside and a hairline inside give the bezel its depth, without a shadow.
    val edge = max(1.dp.toPx(), g.bezelWidth.toPx() * 0.12f)
    drawCircle(bezelEdge, radius = g.bezelOuter.toPx() - edge / 2f, center = centre, style = Stroke(edge))
    drawCircle(display, radius = g.displayRadius.toPx(), center = centre)
}

private fun DrawScope.drawTwelveMark(g: DialGeometry, ink: Color) {
    val r = g.displayRadius.toPx()
    val w = max(2.dp.toPx(), g.diameter.toPx() * 0.018f)
    drawLine(ink, Offset(center.x, center.y - r * 0.93f), Offset(center.x, center.y - r * 0.76f), strokeWidth = w, cap = StrokeCap.Round)
}

private fun DrawScope.drawHand(g: DialGeometry, angle: Float, ink: Color, hand: DialHand) {
    val r = g.displayRadius.toPx()
    val d = g.diameter.toPx()
    val (length, width) = when (hand) {
        DialHand.Minute -> r * 0.84f to max(2.dp.toPx(), d * 0.026f)
        DialHand.Hour -> r * 0.56f to max(2.5.dp.toPx(), d * 0.04f)
    }
    val tail = r * 0.14f
    rotate(angle, pivot = center) {
        drawLine(ink, Offset(center.x, center.y + tail), Offset(center.x, center.y - length), strokeWidth = width, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawPivot(g: DialGeometry, cap: Color, accent: Color) {
    val d = g.diameter.toPx()
    drawCircle(cap, radius = max(3.dp.toPx(), d * 0.034f), center = center)
    drawCircle(accent, radius = max(1.5.dp.toPx(), d * 0.014f), center = center)
}

/** What the black display shows: the face's date line, or a page's icon and label. Fixed in size, as a picture. */
@Composable
private fun DisplayContent(screen: WatchScreen, date: LocalDate?, g: DialGeometry) {
    val c = NormPlusTheme.colors
    val density = LocalDensity.current
    val base = NormPlusTheme.type.dialText
    fun sp(dp: Dp) = with(density) { dp.toSp() }
    val r = g.displayRadius
    if (screen == WatchScreen.Face) {
        if (date == null) return
        val pattern = stringResource(R.string.dial_date_pattern)
        val locale = currentLocale()
        val words = remember(date, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(date) }
        Text(
            words.uppercase(locale),
            style = base.copy(fontSize = sp(r * 0.13f), lineHeight = sp(r * 0.16f), letterSpacing = sp(r * 0.012f)),
            color = c.dialInkSecondary,
            maxLines = 1,
            modifier = Modifier.offset(y = r * 0.5f),
        )
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(r * 1.5f)) {
        screen.icon?.let { Icon(it, null, Modifier.size(r * 0.42f), tint = c.dialAccent) }
        Spacer(Modifier.height(r * 0.08f))
        Text(
            stringResource(screen.label),
            style = base.copy(fontSize = sp(r * 0.15f), lineHeight = sp(r * 0.19f)),
            color = c.dialInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun dialDescription(screen: WatchScreen, time: LocalTime, date: LocalDate?): String {
    val clock = rememberClockFormatter()
    return if (screen == WatchScreen.Face) {
        val at = clock(LocalDateTime.of(date ?: LocalDate.of(2000, 1, 1), time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        if (date != null) stringResource(R.string.dial_face_spoken_date, at, dateSpokenText(date))
        else stringResource(R.string.dial_face_spoken, at)
    } else {
        stringResource(R.string.dial_screen_spoken, stringResource(screen.label))
    }
}

/**
 * An angle that sweeps to [target] the shortest way round: 59 → 0 minutes moves forward one
 * minute, not back round the dial.
 */
@Composable
private fun rememberSweptAngle(target: Float): Float {
    var unwrapped by remember { mutableFloatStateOf(target) }
    var last by remember { mutableFloatStateOf(target) }
    if (target != last) {
        var delta = (target - last) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        unwrapped += delta
        last = target
    }
    val shown by animateFloatAsState(unwrapped, NormMotion.handSweep(NormPlusTheme.animationsRemoved), label = "hand")
    return shown
}

/**
 * The time the dial shows, kept live: it changes at each minute (the watch has no second
 * hand, so nothing finer is drawn).
 */
@Composable
fun rememberWatchTime(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = LocalDateTime.now()
            delay(((60 - current.second) * 1000L - current.nano / 1_000_000L).coerceAtLeast(1L))
            now = LocalDateTime.now().withSecond(0).withNano(0)
        }
    }
    return now
}

/** A pager state for [DialPager], on the face. */
@Composable
fun rememberDialPagerState(screens: List<WatchScreen>): PagerState = rememberPagerState { screens.size }

/**
 * Moves the dial's pager to [page] (Earlier / Later), a cut with animations removed.
 */
suspend fun PagerState.moveDialTo(page: Int, animationsRemoved: Boolean) {
    if (animationsRemoved) scrollToPage(page) else animateScrollToPage(page, animationSpec = NormMotion.dialPage(false))
}

/**
 * The Watch tab's hero: the watch drawn live in its ring and glow, swiping through the
 * watch's own screens in their order with the neighbours peeking at the edges, the screen's
 * name under it, and the page dots. TalkBack reads each page and pages with its scroll
 * actions.
 *
 * @param screens the watch's screens in its order ([WatchScreen.inWatchOrder]).
 * @param caption a line under the screen's name, for the page shown (Watch decides its words).
 */
@Composable
fun DialPager(
    screens: List<WatchScreen>,
    time: LocalTime,
    date: LocalDate,
    state: PagerState,
    modifier: Modifier = Modifier,
    dialFraction: Float = 0.62f,
    caption: (@Composable (WatchScreen) -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val dial = maxWidth * dialFraction
            Box(Modifier.size(dial).heroGlow(spread = 1.55f))
            HorizontalPager(
                state = state,
                contentPadding = PaddingValues(horizontal = (maxWidth - dial) / 2),
                pageSpacing = spacing.l,
                modifier = Modifier.fillMaxWidth().height(dial),
            ) { page ->
                val offset = abs((state.currentPage - page) + state.currentPageOffsetFraction).coerceIn(0f, 1f)
                WatchDial(
                    time = time,
                    screen = screens[page],
                    date = date,
                    modifier = Modifier
                        .size(dial)
                        .graphicsLayer {
                            val s = lerp(1f, NEIGHBOUR_SCALE, offset)
                            scaleX = s
                            scaleY = s
                            alpha = lerp(1f, NEIGHBOUR_ALPHA, offset)
                        },
                )
            }
        }
        val current = screens.getOrNull(state.currentPage) ?: WatchScreen.Face
        Text(
            stringResource(current.label),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.l),
        )
        if (caption != null) Box(Modifier.padding(top = spacing.xs)) { caption(current) }
        PageDots(count = screens.size, current = state.currentPage, modifier = Modifier.padding(top = spacing.l))
    }
}

private const val NEIGHBOUR_SCALE = 0.78f
private const val NEIGHBOUR_ALPHA = 0.5f

/**
 * Page dots: one per page, the current one stretched into a violet pill. Decorative (the
 * pager tells TalkBack where it is); the pill's stretch is a cut with animations removed.
 */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(DotGap), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = i == current
            val width by animateDpAsState(if (active) DotActive else DotSize, NormMotion.stateChange(NormPlusTheme.animationsRemoved), label = "dot")
            Box(
                Modifier
                    .size(width, DotSize)
                    .background(if (active) scheme.primary else c.dialBezelEdge.copy(alpha = 0.9f), NormPlusTheme.shapes.pill),
            )
        }
    }
}

private val DotSize = 7.dp
private val DotActive = 22.dp
private val DotGap = 7.dp

/**
 * A watch screen as a round thumbnail, for the order of the watch's screens (#103): the
 * screen drawn small, its place in the order on a badge, its name under it. The selected one
 * is ringed in violet and its badge filled. Selectable when [onClick] is given.
 */
@Composable
fun WatchScreenThumbnail(
    screen: WatchScreen,
    number: Int,
    selected: Boolean,
    time: LocalTime,
    modifier: Modifier = Modifier,
    date: LocalDate? = null,
    size: Dp = 76.dp,
    onClick: (() -> Unit)? = null,
) {
    val spacing = NormPlusTheme.spacing
    val scheme = MaterialTheme.colorScheme
    val c = NormPlusTheme.colors
    val name = stringResource(screen.label)
    val spoken = stringResource(R.string.watch_screen_thumbnail_spoken, number, name)
    val select = if (onClick != null) Modifier.selectable(selected = selected, onClick = onClick, role = Role.Tab) else Modifier
    Column(
        modifier
            .then(select)
            .clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
                if (onClick != null) role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size + RingGap * 2 + RingWidth * 2)) {
            if (selected) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = RingWidth.toPx()
                    drawCircle(scheme.primary, radius = this.size.minDimension / 2f - w / 2f, style = Stroke(w))
                }
            }
            WatchDial(
                time = time,
                screen = screen,
                date = date,
                ring = false,
                modifier = Modifier.size(size).align(Alignment.Center),
            )
            Surface(
                shape = NormPlusTheme.shapes.pill,
                color = if (selected) scheme.primary else c.raised,
                contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Box(Modifier.size(Badge), contentAlignment = Alignment.Center) {
                    Text(countText(number), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.xs).width(size + RingGap * 2 + RingWidth * 2),
            textAlign = TextAlign.Center,
        )
    }
}

private val RingWidth = 3.dp
private val RingGap = 3.dp
private val Badge = 24.dp
