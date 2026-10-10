package com.normplus.ui.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.HtmlReportWriter
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.SnapshotVerifier
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.resources.Density
import com.android.resources.NightMode
import com.normplus.ui.theme.NormPlusTheme

/*
 * Screenshot tests (#96): Paparazzi renders composables on the JVM, no device. Every shared
 * component, and every rebuilt screen, is rendered in the three [Variant]s and compared with
 * its golden image in app/src/test/snapshots/images/ on every `:app:testDebugUnitTest`.
 * docs/app.md ("UI Layer") says how to add a screen's tests and re-record goldens.
 */

/**
 * The renders every screen and component is checked in: light, dark, and font scale 1.3 in
 * dark (the app is dark first, so that is where most people meet the large font).
 */
enum class Variant(val dark: Boolean, val fontScale: Float) {
    Light(dark = false, fontScale = 1f),
    Dark(dark = true, fontScale = 1f),
    LargeFont(dark = true, fontScale = 1.3f),
    ;

    override fun toString() = name.lowercase()
}

/** How far a render may drift from its golden, in percent of pixels: Paparazzi's default. */
private const val MAX_PERCENT_DIFFERENCE = 0.1

/** The Pixel 8 the app is checked on (1080 x 2400, 420 dpi, 411 dp wide). */
val Pixel8: DeviceConfig = DeviceConfig.PIXEL_5.copy(
    screenWidth = 1080,
    screenHeight = 2400,
    xdpi = 428,
    ydpi = 428,
    density = Density.create(420),
)

/**
 * A Paparazzi rule for [variant]. [component] = true shrinks the image to what is drawn (for
 * a component); false renders the whole Pixel 8 screen (for a screen).
 */
fun normPaparazzi(variant: Variant, component: Boolean = true): Paparazzi {
    // Every test run verifies against the goldens (#96). Paparazzi's own default only verifies
    // under verifyPaparazziDebug and merely renders under the plain test task, so the handler
    // is chosen here: record under recordPaparazziDebug (the plugin sets paparazzi.test.record),
    // verify otherwise. A test with no golden yet fails until it is recorded.
    val recording = System.getProperty("paparazzi.test.record")?.toBoolean() == true
    return Paparazzi(
        deviceConfig = Pixel8.copy(
            nightMode = if (variant.dark) NightMode.NIGHT else NightMode.NOTNIGHT,
            fontScale = variant.fontScale,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        renderingMode = if (component) RenderingMode.SHRINK else RenderingMode.NORMAL,
        showSystemUi = false,
        maxPercentDifference = MAX_PERCENT_DIFFERENCE,
        snapshotHandler = if (recording) HtmlReportWriter() else SnapshotVerifier(MAX_PERCENT_DIFFERENCE),
    )
}

/**
 * The frame a component is rendered in: the theme (dark from the device's night mode, as in
 * the app), on the page's ground, with the screen's side margin.
 */
@Composable
fun ComponentFrame(content: @Composable () -> Unit) {
    NormPlusTheme(animationsRemoved = true) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(NormPlusTheme.spacing.gutter),
        ) { content() }
    }
}
