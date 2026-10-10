package com.normplus.ui.screens.daydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.normplus.ui.screens.daydetail.DayDetailSnapshotTest.Companion.clock
import com.normplus.ui.screens.daydetail.DayDetailSnapshotTest.Companion.day
import com.normplus.ui.screens.daydetail.DayDetailSnapshotTest.Companion.past
import com.normplus.ui.screens.daydetail.DayDetailSnapshotTest.Companion.today
import com.normplus.ui.screens.daydetail.DayDetailSnapshotTest.Companion.zone
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import com.normplus.ui.theme.NormPlusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The cards below Day detail's tiles, each on its own: a whole-screen render shows only the
 * first viewport, and scrolling a list in a render leaves the header unaware of it.
 */
@RunWith(Parameterized::class)
class DayDetailCardsSnapshotTest(variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun steps() = paparazzi.snapshot { ComponentFrame { StepsCard(day(past, scale = 0.6), clock, zone) } }

    /** Today, filling: the half hours after the last sync are not there yet. */
    @Test
    fun stepsToday() = paparazzi.snapshot { ComponentFrame { StepsCard(day(today, scale = 0.5, until = 29), clock, zone) } }

    @Test
    fun heartRate() = paparazzi.snapshot { ComponentFrame { HeartCard(day(past), clock, zone) } }

    /** One reading only: a dot, and its figure without a range. */
    @Test
    fun heartRateOneReading() = paparazzi.snapshot { ComponentFrame { HeartCard(day(past, readings = 1), clock, zone) } }

    @Test
    fun sleep() = paparazzi.snapshot { ComponentFrame { SleepCard(day(past), clock) } }

    /** A nap that ended the same day: a band per session, each with its times. */
    @Test
    fun sleepWithNap() = paparazzi.snapshot { ComponentFrame { SleepCard(day(past, nap = true), clock) } }

    /** No record of any kind: each card says what is missing. */
    @Test
    fun absent() = paparazzi.snapshot {
        val d = DayBuilder(zone).build(past, emptyList(), emptyList(), emptyList(), emptyList())
        ComponentFrame { Cards { StepsCard(d, clock, zone); HeartCard(d, clock, zone); SleepCard(d, clock) } }
    }

    @Test
    fun rightToLeft() = paparazzi.snapshot {
        ComponentFrame {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Cards { StepsCard(day(past, scale = 0.6), clock, zone); SleepCard(day(past, nap = true), clock) }
            }
        }
    }

    @Composable
    private fun Cards(content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.l)) { content() }
    }
}
