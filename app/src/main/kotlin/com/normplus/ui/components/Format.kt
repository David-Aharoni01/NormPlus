package com.normplus.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import com.normplus.R
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * How Norm+ prints numbers, times, durations and dates. Every screen formats through
 * these, so a figure looks the same on Today, in History and on Watch.
 */

/** The locale the UI is shown in. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

/** A count with the locale's grouping: 6,412. */
fun formatCount(value: Int, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

/** [formatCount] in the UI's locale. */
@Composable
@ReadOnlyComposable
fun countText(value: Int): String = formatCount(value, currentLocale())

/**
 * A clock time as the phone shows it: 14:05, or 2:05 PM when the phone is set to 12-hour.
 * Remember the formatter once per screen with [rememberClockFormatter].
 */
@Composable
fun rememberClockFormatter(): (epochMillis: Long) -> String {
    val context = LocalContext.current
    val locale = currentLocale()
    val is24 = remember(context) { DateFormat.is24HourFormat(context) }
    return remember(locale, is24) {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hm")
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        val zone = ZoneId.systemDefault()
        val format: (Long) -> String = { formatter.format(Instant.ofEpochMilli(it).atZone(zone)) }
        format
    }
}

/** A duration in running text: "6 h 48 m", or "48 m" under an hour ([durationFigure] for a figure). */
@Composable
@ReadOnlyComposable
fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) stringResource(R.string.duration_hours_minutes, h, m)
    else stringResource(R.string.duration_minutes, m)
}

/** A date line: "Thursday · 9 October" (the dial's, a day's header). */
@Composable
fun dateLineText(date: LocalDate): String {
    val locale = currentLocale()
    val pattern = stringResource(R.string.date_line_pattern)
    return remember(date, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(date) }
}

/** The same date for TalkBack, without the printer's dot: "Thursday 9 October". */
@Composable
fun dateSpokenText(date: LocalDate): String {
    val locale = currentLocale()
    val pattern = stringResource(R.string.date_spoken_pattern)
    return remember(date, pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale).format(date) }
}
