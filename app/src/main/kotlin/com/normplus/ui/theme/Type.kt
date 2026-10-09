package com.normplus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.normplus.R

/*
 * One family, Roboto Flex (res/font/roboto_flex.ttf, the variable font from google/fonts,
 * SIL OFL 1.1, the licence in assets/licenses/). Its axes make the two cuts the world asks
 * for out of the one file:
 *
 *  - the condensed heavy cut, for date numerals and big figures (wdth 30, wght 800, the
 *    tallest figures YTFI 788, optical size 144 for display sizes and 28 for row numerals);
 *  - the normal cut for everything else (wdth 100), at two optical sizes: 14 for text and
 *    labels, 28 for headlines and titles.
 *
 * Dark mode lowers the grade (GRAD -25): light ink on a dark sheet reads heavier than the
 * same weight printed dark on white, and grade thins the strokes without changing a single
 * advance width, so nothing reflows between the schemes.
 *
 * Figures: Roboto Flex's default figures are already tabular (its GSUB carries `pnum`, not
 * `tnum`). Every style still asks for `tnum`, so the intent survives a change of face.
 *
 * Sizes are the Material type roles, in sp, so they follow the system font size.
 */

private const val FIGURES = "tnum"
private const val TEXT_OPSZ = 14f
private const val HEADLINE_OPSZ = 28f
private const val NUMERAL_DISPLAY_OPSZ = 144f
private const val NUMERAL_TEXT_OPSZ = 28f
private const val CONDENSED_WIDTH = 30f
private const val TALL_FIGURES = 788f       // YTFI, the axis maximum
internal const val DARK_GRADE = -25
internal const val LIGHT_GRADE = 0

@OptIn(ExperimentalTextApi::class) // Font(resId, variationSettings): stable on API 26+, still marked
private fun flex(
    weight: Int,
    opsz: Float,
    grade: Int,
    width: Float = 100f,
    figureHeight: Float? = null,
): Font {
    val settings = buildList {
        add(FontVariation.weight(weight))
        add(FontVariation.width(width))
        add(FontVariation.grade(grade))
        add(FontVariation.Setting("opsz", opsz))
        if (figureHeight != null) add(FontVariation.Setting("YTFI", figureHeight))
    }
    return Font(
        resId = R.font.roboto_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(*settings.toTypedArray()),
    )
}

/** The four font families, built once per grade. */
internal class FlexFamilies(grade: Int) {
    val text = FontFamily(
        flex(400, TEXT_OPSZ, grade), flex(500, TEXT_OPSZ, grade),
        flex(600, TEXT_OPSZ, grade), flex(700, TEXT_OPSZ, grade),
    )
    val headline = FontFamily(
        flex(400, HEADLINE_OPSZ, grade), flex(500, HEADLINE_OPSZ, grade),
        flex(600, HEADLINE_OPSZ, grade), flex(700, HEADLINE_OPSZ, grade),
    )
    val numeralDisplay = FontFamily(
        flex(800, NUMERAL_DISPLAY_OPSZ, grade, CONDENSED_WIDTH, TALL_FIGURES),
    )
    val numeralText = FontFamily(
        flex(700, NUMERAL_TEXT_OPSZ, grade, CONDENSED_WIDTH, TALL_FIGURES),
        flex(800, NUMERAL_TEXT_OPSZ, grade, CONDENSED_WIDTH, TALL_FIGURES),
    )
}

/** A numeral set solid: the line box is the figure, trimmed above the cap and below the baseline. */
private val Solid = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun style(
    family: FontFamily,
    weight: Int,
    size: Int,
    lineHeight: Int,
    tracking: Double,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = FIGURES,
)

internal fun normTypography(f: FlexFamilies) = Typography(
    // Display roles are figures here (Material: "short, important text or numerals"),
    // so they take the condensed heavy cut.
    displayLarge = style(f.numeralDisplay, 800, 57, 64, 0.0),
    displayMedium = style(f.numeralDisplay, 800, 45, 52, 0.0),
    displaySmall = style(f.numeralText, 800, 36, 44, 0.0),
    headlineLarge = style(f.headline, 600, 32, 40, -0.25),
    headlineMedium = style(f.headline, 600, 28, 36, -0.15),
    headlineSmall = style(f.headline, 600, 24, 32, 0.0),
    titleLarge = style(f.headline, 600, 22, 28, 0.0),
    titleMedium = style(f.text, 600, 16, 24, 0.1),
    titleSmall = style(f.text, 600, 14, 20, 0.1),
    bodyLarge = style(f.text, 400, 16, 24, 0.3),
    bodyMedium = style(f.text, 400, 14, 20, 0.2),
    bodySmall = style(f.text, 400, 12, 16, 0.3),
    labelLarge = style(f.text, 600, 14, 20, 0.1),
    labelMedium = style(f.text, 500, 12, 16, 0.4),
    labelSmall = style(f.text, 500, 11, 16, 0.4),
)

/**
 * The Day Sheet's own type, beyond the Material roles. Read as `NormPlusTheme.type`.
 *
 * @property numeral the day's figure on a sheet, condensed and huge (Today's steps).
 *   [com.normplus.ui.components.DateNumeral] shrinks it to fit, so 40,000 fits at font scale 1.3.
 * @property numeralMedium a big figure that is not the day's (the watch card's battery).
 * @property numeralSmall the date numeral at the head of a History row or a sheet's edge.
 * @property dateLine tracked capitals for the date: "THURSDAY · 9 OCTOBER".
 * @property almanacLabel / [almanacValue] the almanac lines under the figure.
 * @property status the quiet status line under a top app bar's title.
 * @property chartLabel axis and goal labels on a chart.
 */
@Immutable
data class NormType(
    val numeral: TextStyle,
    val numeralMedium: TextStyle,
    val numeralSmall: TextStyle,
    val dateLine: TextStyle,
    val almanacLabel: TextStyle,
    val almanacValue: TextStyle,
    val status: TextStyle,
    val chartLabel: TextStyle,
)

internal fun normType(f: FlexFamilies) = NormType(
    numeral = style(f.numeralDisplay, 800, 120, 120, 0.0).copy(lineHeightStyle = Solid),
    numeralMedium = style(f.numeralDisplay, 800, 64, 64, 0.0).copy(lineHeightStyle = Solid),
    numeralSmall = style(f.numeralText, 800, 30, 32, 0.0).copy(lineHeightStyle = Solid),
    dateLine = style(f.text, 600, 13, 16, 0.0).copy(letterSpacing = 0.16.em),
    almanacLabel = style(f.text, 500, 13, 20, 0.2),
    almanacValue = style(f.text, 400, 14, 20, 0.1),
    status = style(f.text, 500, 13, 16, 0.2),
    chartLabel = style(f.text, 500, 11, 14, 0.3),
)
