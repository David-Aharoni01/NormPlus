package com.normplus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and sizes. Screens take every distance from here, never a literal.
 *
 * @property gutter the screen's side margin (and the plate's inset from the screen edge).
 * @property sheetPadding the inside margin of a day sheet.
 * @property touchTarget the smallest touch target, 48 dp, Material's and the brief's.
 * @property hairline rules, the perforation and the goal line.
 */
@Immutable
data class NormSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    val gutter: Dp = 16.dp,
    val sheetPadding: Dp = 24.dp,
    val touchTarget: Dp = 48.dp,
    val hairline: Dp = 1.dp,
    val progressLine: Dp = 3.dp,
    val icon: Dp = 24.dp,
    val markIcon: Dp = 16.dp,
)

private val LocalNormColors = staticCompositionLocalOf { LightNormColors }
private val LocalNormType = staticCompositionLocalOf<NormType> { error("NormPlusTheme is not applied") }
private val LocalNormSpacing = staticCompositionLocalOf { NormSpacing() }
private val LocalNormShapes = staticCompositionLocalOf { NormShapeSet() }
private val LocalAnimationsRemoved = staticCompositionLocalOf { false }
private val LocalBaseColorScheme = staticCompositionLocalOf { LightColorScheme }

private val LightFamilies by lazy { FlexFamilies(LIGHT_GRADE) }
private val DarkFamilies by lazy { FlexFamilies(DARK_GRADE) }

/**
 * The Norm+ theme: the Day Sheet world as Material 3 values (#96).
 *
 * Light and dark follow the system; there is no Dynamic Color, because the palette is the
 * world. Material components read [MaterialTheme]; the Day Sheet's own roles are on
 * [NormPlusTheme]. [animationsRemoved] is normally read from the system and is a parameter
 * only so screenshot tests can set it.
 */
@Composable
fun NormPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    animationsRemoved: Boolean = rememberAnimationsRemoved(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colors = if (darkTheme) DarkNormColors else LightNormColors
    val families = if (darkTheme) DarkFamilies else LightFamilies
    val typography = remember(families) { normTypography(families) }
    val type = remember(families) { normType(families) }
    CompositionLocalProvider(
        LocalNormColors provides colors,
        LocalNormType provides type,
        LocalNormSpacing provides NormSpacing(),
        LocalNormShapes provides NormShapeSet(),
        LocalAnimationsRemoved provides animationsRemoved,
        LocalBaseColorScheme provides scheme,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = NormShapes, content = content)
    }
}

/** The Day Sheet's own roles, beside [MaterialTheme]'s. */
object NormPlusTheme {
    val colors: NormColors
        @Composable @ReadOnlyComposable get() = LocalNormColors.current
    val type: NormType
        @Composable @ReadOnlyComposable get() = LocalNormType.current
    val spacing: NormSpacing
        @Composable @ReadOnlyComposable get() = LocalNormSpacing.current
    val shapes: NormShapeSet
        @Composable @ReadOnlyComposable get() = LocalNormShapes.current
    val animationsRemoved: Boolean
        @Composable @ReadOnlyComposable get() = LocalAnimationsRemoved.current

    /** The scheme of the page itself, even inside a plate (where MaterialTheme is reversed). */
    internal val baseColorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = LocalBaseColorScheme.current
}

/** Prints [content] reversed, as on the plate: Material components inside come out light on ink. */
@Composable
internal fun ReversedColors(content: @Composable () -> Unit) {
    val base = NormPlusTheme.baseColorScheme
    val colors = NormPlusTheme.colors
    val reversed = remember(base, colors) { plateColorScheme(base, colors) }
    MaterialTheme(colorScheme = reversed, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** Prints [content] on paper again, inside a plate: the page's own scheme. */
@Composable
internal fun PageColors(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NormPlusTheme.baseColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
