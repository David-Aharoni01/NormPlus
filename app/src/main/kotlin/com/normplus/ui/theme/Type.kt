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
import androidx.compose.ui.unit.sp
import com.normplus.R

/*
 * One family, Roboto Flex (res/font/roboto_flex.ttf, the variable font from google/fonts,
 * SIL OFL 1.1, the licence in assets/licenses/). Dark Dial asks three things of it:
 *
 *  - big bold titles: the heavy weights at a display optical size (opsz 36), so the large
 *    title on every screen is tight and dark;
 *  - large bold figures: the same weights at opsz 72 for tiles and opsz 144 for the hero
 *    figure, where Roboto Flex draws its finest display shapes;
 *  - plain text for everything else, at the text optical size (opsz 14).
 *
 * Android applies no optical size of its own, so each family names its opsz.
 *
 * Dark mode lowers the grade (GRAD -25): light words on a dark ground read heavier than the
 * same weight dark on white, and grade thins the strokes without changing a single advance
 * width, so nothing reflows between the schemes.
 *
 * Figures: Roboto Flex's default figures are already tabular (its GSUB carries `pnum`, not
 * `tnum`). Every style still asks for `tnum`, so the intent survives a change of face.
 *
 * Sizes are the Material type roles, in sp, so they follow the system font size.
 */

private const val FIGURES = "tnum"
private const val TEXT_OPSZ = 14f
private const val TITLE_OPSZ = 36f
private const val FIGURE_OPSZ = 72f
private const val HERO_OPSZ = 144f
internal const val DARK_GRADE = -25
internal const val LIGHT_GRADE = 0

@OptIn(ExperimentalTextApi::class) // Font(resId, variationSettings): stable on API 26+, still marked
private fun flex(weight: Int, opsz: Float, grade: Int): Font = Font(
    resId = R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.grade(grade),
        FontVariation.Setting("opsz", opsz),
    ),
)

/** The four families, built once per grade. */
internal class FlexFamilies(grade: Int) {
    val text = FontFamily(
        flex(400, TEXT_OPSZ, grade), flex(500, TEXT_OPSZ, grade),
        flex(600, TEXT_OPSZ, grade), flex(700, TEXT_OPSZ, grade),
    )
    val title = FontFamily(
        flex(600, TITLE_OPSZ, grade), flex(700, TITLE_OPSZ, grade), flex(800, TITLE_OPSZ, grade),
    )
    val figure = FontFamily(flex(700, FIGURE_OPSZ, grade), flex(800, FIGURE_OPSZ, grade))
    val hero = FontFamily(flex(800, HERO_OPSZ, grade))
}

/** A figure set solid: the line box is the figure, trimmed above the cap and below the baseline. */
private val Solid = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun style(family: FontFamily, weight: Int, size: Int, lineHeight: Int, tracking: Double) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = FIGURES,
)

internal fun normTypography(f: FlexFamilies) = Typography(
    // Display roles are figures here (Material: "short, important text or numerals").
    displayLarge = style(f.hero, 800, 57, 64, -1.0),
    displayMedium = style(f.figure, 800, 45, 52, -0.5),
    displaySmall = style(f.figure, 800, 36, 44, -0.25),
    // Headlines and the large title roles: the heavy display cut.
    headlineLarge = style(f.title, 800, 32, 40, -0.5),
    headlineMedium = style(f.title, 800, 28, 36, -0.25),
    headlineSmall = style(f.title, 700, 24, 32, 0.0),
    titleLarge = style(f.title, 700, 22, 28, 0.0),
    titleMedium = style(f.text, 600, 16, 24, 0.1),
    titleSmall = style(f.text, 600, 14, 20, 0.1),
    bodyLarge = style(f.text, 400, 16, 24, 0.2),
    bodyMedium = style(f.text, 400, 14, 20, 0.2),
    bodySmall = style(f.text, 400, 12, 16, 0.3),
    labelLarge = style(f.text, 600, 14, 20, 0.1),
    labelMedium = style(f.text, 600, 12, 16, 0.3),
    labelSmall = style(f.text, 500, 11, 16, 0.4),
)

/**
 * Dark Dial's own type, beyond the Material roles. Read as `NormPlusTheme.type`.
 *
 * @property screenTitle the large bold title at the head of every screen ("Today").
 * @property screenTitleCollapsed the same title once the header has collapsed on scroll.
 * @property heroFigure the hero card's figure (Today's steps). [FitText][com.normplus.ui.components.FitText]
 *   shrinks it to fit, so 40,000 fits at font scale 1.3.
 * @property tileFigure a metric tile's figure ("72", "6 h 48 m").
 * @property figureUnit the unit set beside a figure ("bpm", "h"), smaller and quieter.
 * @property cardTitle the label at the head of a card ("Steps", "Sleep").
 * @property sectionTitle the heading over a group of settings ("Notifications").
 * @property pill the words in a status pill, a state mark or a banner's action.
 * @property chartLabel axis and goal labels on a chart.
 * @property dialText words drawn on the watch's display; the dial scales them to its size.
 */
@Immutable
data class NormType(
    val screenTitle: TextStyle,
    val screenTitleCollapsed: TextStyle,
    val heroFigure: TextStyle,
    val tileFigure: TextStyle,
    val figureUnit: TextStyle,
    val cardTitle: TextStyle,
    val sectionTitle: TextStyle,
    val pill: TextStyle,
    val chartLabel: TextStyle,
    val dialText: TextStyle,
)

internal fun normType(f: FlexFamilies) = NormType(
    screenTitle = style(f.title, 800, 34, 40, -0.5),
    screenTitleCollapsed = style(f.title, 800, 22, 28, -0.2),
    heroFigure = style(f.hero, 800, 64, 64, -1.0).copy(lineHeightStyle = Solid),
    tileFigure = style(f.figure, 800, 28, 32, -0.4).copy(lineHeightStyle = Solid),
    figureUnit = style(f.text, 600, 15, 20, 0.1),
    cardTitle = style(f.text, 600, 15, 20, 0.1),
    sectionTitle = style(f.title, 700, 18, 24, 0.0),
    pill = style(f.text, 600, 14, 20, 0.1),
    chartLabel = style(f.text, 500, 11, 14, 0.3),
    dialText = style(f.text, 600, 14, 18, 0.2),
)
