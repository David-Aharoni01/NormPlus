package com.normplus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
 * @property gutter the screen's side margin.
 * @property cardPadding the inside margin of a card.
 * @property touchTarget the smallest touch target, 48 dp, Material's and the brief's.
 * @property hairline the line round a card, dividers, guides and the goal line.
 * @property progressBar the thickness of a progress bar (the hero card's).
 * @property capsuleHeight the floating navigation capsule; [capsuleGap] its distance from
 *   the screen's bottom edge (above the system navigation bar) and sides.
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
    val cardPadding: Dp = 20.dp,
    val touchTarget: Dp = 48.dp,
    val hairline: Dp = 1.dp,
    val progressBar: Dp = 8.dp,
    val icon: Dp = 24.dp,
    val smallIcon: Dp = 18.dp,
    val markIcon: Dp = 16.dp,
    val capsuleHeight: Dp = 72.dp,
    val capsuleGap: Dp = 12.dp,
)

private val LocalNormColors = staticCompositionLocalOf { DarkNormColors }
private val LocalNormType = staticCompositionLocalOf<NormType> { error("NormPlusTheme is not applied") }
private val LocalNormSpacing = staticCompositionLocalOf { NormSpacing() }
private val LocalNormShapes = staticCompositionLocalOf { NormShapeSet() }
private val LocalAnimationsRemoved = staticCompositionLocalOf { false }
private val LocalDarkTheme = staticCompositionLocalOf { true }

private val LightFamilies by lazy { FlexFamilies(LIGHT_GRADE) }
private val DarkFamilies by lazy { FlexFamilies(DARK_GRADE) }

/**
 * The Norm+ theme: the Dark Dial world as Material 3 values (#96).
 *
 * Dark first, with a designed light scheme; both follow the system. There is no Dynamic
 * Color, because the palette is the world. Material components read [MaterialTheme]; Dark
 * Dial's own roles are on [NormPlusTheme]. [animationsRemoved] is normally read from the
 * system and is a parameter only so screenshot tests can set it.
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
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = NormShapes, content = content)
    }
}

/** Dark Dial's own roles, beside [MaterialTheme]'s. */
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

    /** Whether the dark scheme is in use (the system bars' icons follow it, #97). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalDarkTheme.current
}
