package com.normplus.ui.navigation

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Every screen of Norm+, as a type-safe Navigation Compose route (#97). The shell owns these
 * and the graphs they sit in ([AppNavGraph], `ui/shell/TabsHost`); a screen never navigates
 * itself, it calls the callbacks its entry composable is given. docs/app.md ("UI Layer", "The
 * navigation contract") has the table: route, entry composable, owning issue.
 *
 * Two levels: the app's graph holds [FirstRun], [Tabs] and every screen below a tab; [Tabs]
 * holds the three tabs, under one header and the floating navigation capsule.
 */
@Serializable
sealed interface Destination {

    /** First run (#98): the start when no watch is saved. */
    @Serializable data object FirstRun : Destination

    /** The three tabs under the shell's header and navigation capsule: the start otherwise. */
    @Serializable data object Tabs : Destination

    /** Tab: how the day is going (#99). The start of [Tabs]. */
    @Serializable data object Today : Destination

    /** Tab: steps, heart rate, sleep and calories by day (#100). */
    @Serializable data object History : Destination

    /** Tab: the watch, its fix-its and its settings (#102). */
    @Serializable data object Watch : Destination

    /** A past day (#101), from History's rows and from Today's Yesterday row. */
    @Serializable data class DayDetail(val epochDay: Long) : Destination {
        constructor(date: LocalDate) : this(date.toEpochDay())

        val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
    }

    /** The order of the watch's own screens (#103), from Watch. */
    @Serializable data object WatchScreens : Destination

    /** Which apps may notify the watch (#104), from Watch (and from first run). */
    @Serializable data object NotificationApps : Destination

    /** Re-aligning the hands (#105), a full-screen flow from Watch. */
    @Serializable data object HandsCalibration : Destination

    /**
     * Updating the watch (#106), a full-screen flow from Watch. [customFile]: a `.bin` chosen on
     * Technical (#107), a content URI, sent through the same deliberate steps.
     */
    @Serializable data class FirmwareUpdate(val customFile: String? = null) : Destination

    /** The details ordinary users need not see (#107), from the bottom of Watch. */
    @Serializable data object Technical : Destination
}
