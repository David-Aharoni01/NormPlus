package com.normplus.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.normplus.R
import com.normplus.ui.snapshot.ComponentFrame
import com.normplus.ui.snapshot.Variant
import com.normplus.ui.snapshot.normPaparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The theme itself: the palette's roles, the type scale, and the launcher icon's layers. */
@RunWith(Parameterized::class)
class ThemeSnapshotTest(private val variant: Variant) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants() = Variant.entries
    }

    @get:Rule
    val paparazzi = normPaparazzi(variant)

    @Test
    fun palette() = paparazzi.snapshot {
        ComponentFrame {
            val c = NormPlusTheme.colors
            val s = MaterialTheme.colorScheme
            Column(verticalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.xs)) {
                Swatch("plate · shell · onPlate", c.plate, c.onPlate)
                Swatch("primary · filled buttons", s.primary, s.onPrimary)
                Swatch("sheet · surface", c.sheet, s.onSurface)
                Swatch("ground · background", s.background, s.onBackground)
                Swatch("sheet below", c.sheetBelow, s.onSurface)
                Swatch("red letter", c.sheet, c.redLetter)
                Swatch("needs fixing", c.needsFixing, c.onNeedsFixing)
                Swatch("error container", s.errorContainer, s.onErrorContainer)
                Swatch("secondary container", s.secondaryContainer, s.onSecondaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.xs)) {
                    listOf(c.chartBar, c.redLetter, c.chartGoal, c.chartAxis, c.sleepDeep, c.sleepLight, c.sleepAwake, s.outline, s.outlineVariant)
                        .forEach { Box(Modifier.size(32.dp).background(it).border(NormPlusTheme.spacing.hairline, s.outlineVariant)) }
                }
            }
        }
    }

    @Test
    fun typeScale() = paparazzi.snapshot {
        ComponentFrame {
            val t = MaterialTheme.typography
            val n = NormPlusTheme.type
            Column {
                Sample("numeral", n.numeral, "6,412")
                Sample("numeralMedium", n.numeralMedium, "82%")
                Sample("numeralSmall", n.numeralSmall, "9")
                Sample("dateLine", n.dateLine, "THURSDAY · 9 OCTOBER")
                Sample("displaySmall", t.displaySmall, "1,840")
                Sample("headlineSmall", t.headlineSmall, "Move the minute hand to 12")
                Sample("titleLarge", t.titleLarge, "Today")
                Sample("titleMedium", t.titleMedium, "of 8,000 steps")
                Sample("bodyLarge", t.bodyLarge, "Android will ask to pair with Norm2#1234.")
                Sample("bodyMedium", t.bodyMedium, "Lights the screen when you lift your wrist")
                Sample("almanacValue", n.almanacValue, "72 bpm at 14:05 · 0123456789")
                Sample("labelLarge", t.labelLarge, "Turn on")
                Sample("status", n.status, "Norm 2 · 82% · synced 14:32")
                Sample("chartLabel", n.chartLabel, "00:00  06:00  12:00")
            }
        }
    }

    @Test
    fun launcherIcon() = paparazzi.snapshot {
        ComponentFrame {
            Row(horizontalArrangement = Arrangement.spacedBy(NormPlusTheme.spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(RoundedCornerShape(0.dp), 88)        // the visible 72 dp of the layers, unmasked
                Icon(CircleShape, 64)
                Icon(RoundedCornerShape(30), 64)          // a squircle-ish mask
                Icon(CircleShape, 48)
                MonochromeIcon(48)
            }
        }
    }

    @Composable
    private fun Icon(mask: Shape, size: Int) {
        Box(Modifier.size(size.dp).clip(mask).background(colorResource(R.color.ic_launcher_background))) {
            // An adaptive icon draws its 108 dp layers at 1.5 x the visible 72 dp.
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize((size * 1.5f).dp).align(Alignment.Center),
            )
        }
    }

    @Composable
    private fun MonochromeIcon(size: Int) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)) {
            Image(
                painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
                modifier = Modifier.requiredSize((size * 1.5f).dp).align(Alignment.Center),
            )
        }
    }

    @Composable
    private fun Swatch(name: String, fill: Color, ink: Color) {
        Box(Modifier.background(fill).border(NormPlusTheme.spacing.hairline, MaterialTheme.colorScheme.outlineVariant).padding(NormPlusTheme.spacing.s)) {
            Text(name, color = ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(320.dp))
        }
    }

    @Composable
    private fun Sample(role: String, style: TextStyle, text: String) {
        Column(Modifier.padding(vertical = NormPlusTheme.spacing.xs)) {
            Text(role, style = NormPlusTheme.type.chartLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = style, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Spacer(Modifier.height(NormPlusTheme.spacing.xxs))
        }
    }
}
