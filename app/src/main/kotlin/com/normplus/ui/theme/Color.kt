package com.normplus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * The Day Sheet palette (#95, #96): a tear-off block calendar. Every colour has a job.
 *
 *  - Backing ink-blue: the shell (top app bar, navigation bar), the ONE reversed plate per
 *    screen, and filled buttons.
 *  - Sheet white: the surfaces. Print black: text and numerals.
 *  - Newsprint grey: the ground, and secondary surfaces.
 *  - Calendar red: the red-letter colour (step goal met), and nothing decorative.
 *  - Amber: "needs fixing". Error red: "failed", a different red from the calendar's, and
 *    always an icon plus words in a container, never a bare red figure.
 *
 * Dark is designed, not inverted: a dark sheet on a deeper ink-blue backing, the newsprint
 * becomes the ink, and the red is lighter. Contrast pairs (WCAG) are noted where they were
 * the reason for a value.
 *
 * Screens never use these constants: they read MaterialTheme.colorScheme and
 * NormPlusTheme.colors. They are internal to the theme.
 */

// ── Light ─────────────────────────────────────────────────────────────────────
internal val InkBlue = Color(0xFF1F2A44)            // white on it: 14.3:1
internal val SheetWhite = Color(0xFFFFFFFF)
internal val PrintBlack = Color(0xFF121212)         // on white 18.7:1, on newsprint 15.4:1
internal val Newsprint = Color(0xFFE9E9E6)
internal val CalendarRed = Color(0xFFD62828)        // on white 5.0:1
internal val InkGrey = Color(0xFF4A5163)            // secondary text: on white 7.9:1, on newsprint 6.5:1
internal val InkGreyOnPlate = Color(0xFFAEB7CC)     // secondary text on ink-blue: 7.1:1

// ── Dark ──────────────────────────────────────────────────────────────────────
internal val DeepInk = Color(0xFF111A2D)            // the backing, deeper than InkBlue
internal val NightSheet = Color(0xFF23252C)
internal val NightGround = Color(0xFF0D0E11)
internal val NewsprintInk = Color(0xFFECEBE6)       // text: on the dark sheet 12.8:1
internal val NightRed = Color(0xFFFF7A6E)           // on the dark sheet 6.0:1, on DeepInk 6.8:1
internal val PaleInk = Color(0xFFBCC7E3)            // filled buttons in dark; their label on it 9.5:1
internal val NightGrey = Color(0xFFB3B8C5)          // secondary text: on the dark sheet 7.7:1
internal val PaleInkOnPlate = Color(0xFFA9B3CC)     // secondary text on DeepInk: 8.3:1

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = InkBlue,
    onPrimary = SheetWhite,
    primaryContainer = Color(0xFFD9DEEA),
    onPrimaryContainer = InkBlue,
    inversePrimary = PaleInk,
    secondary = Color(0xFF4D566B),
    onSecondary = SheetWhite,
    secondaryContainer = Color(0xFFDCE0E8),
    onSecondaryContainer = InkBlue,
    // No tertiary accent: calendar red is the only accent, and it is not a Material role
    // (it would leak into components). Tertiary stays in the ink family.
    tertiary = Color(0xFF4D566B),
    onTertiary = SheetWhite,
    tertiaryContainer = Color(0xFFDCE0E8),
    onTertiaryContainer = InkBlue,
    background = Newsprint,
    onBackground = PrintBlack,
    surface = SheetWhite,
    onSurface = PrintBlack,
    surfaceVariant = Color(0xFFE2E2DE),
    onSurfaceVariant = InkGrey,
    surfaceTint = InkBlue,
    inverseSurface = InkBlue,                       // snackbars print white on the backing
    inverseOnSurface = SheetWhite,
    error = Color(0xFFB0223F),                      // crimson, not the calendar's scarlet; on white 6.7:1
    onError = SheetWhite,
    errorContainer = Color(0xFFFADCDD),
    onErrorContainer = Color(0xFF4D0A16),           // 11.9:1 (error on it 5.2:1)
    outline = Color(0xFF6E7483),                    // control boundaries: on white 4.7:1
    outlineVariant = Color(0xFFC8CAD0),             // hairlines
    scrim = Color(0xFF000000),
    surfaceBright = SheetWhite,
    surfaceDim = Color(0xFFDADAD6),
    surfaceContainerLowest = SheetWhite,
    surfaceContainerLow = Color(0xFFF6F6F4),
    surfaceContainer = Color(0xFFF0F0ED),
    surfaceContainerHigh = Newsprint,
    surfaceContainerHighest = Color(0xFFE2E2DE),
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = PaleInk,
    onPrimary = Color(0xFF16203A),
    primaryContainer = Color(0xFF2E3A57),
    onPrimaryContainer = Color(0xFFDCE2F2),
    inversePrimary = InkBlue,
    secondary = Color(0xFFBFC5D3),
    onSecondary = Color(0xFF262D3D),
    secondaryContainer = Color(0xFF343B4C),
    onSecondaryContainer = Color(0xFFDCE0EA),
    tertiary = Color(0xFFBFC5D3),
    onTertiary = Color(0xFF262D3D),
    tertiaryContainer = Color(0xFF343B4C),
    onTertiaryContainer = Color(0xFFDCE0EA),
    background = NightGround,
    onBackground = NewsprintInk,
    surface = NightSheet,
    onSurface = NewsprintInk,
    surfaceVariant = Color(0xFF34373F),
    onSurfaceVariant = NightGrey,
    surfaceTint = PaleInk,
    inverseSurface = NewsprintInk,
    inverseOnSurface = NightSheet,
    error = Color(0xFFFF8F9A),                      // on the dark sheet 7.0:1
    onError = Color(0xFF4A0612),
    errorContainer = Color(0xFF5C1322),
    onErrorContainer = Color(0xFFFFD9DC),           // 10.3:1
    outline = Color(0xFF8A8F9B),
    outlineVariant = Color(0xFF3A3D45),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF393C44),
    surfaceDim = NightGround,
    surfaceContainerLowest = Color(0xFF1A1C21),
    surfaceContainerLow = NightSheet,
    surfaceContainer = Color(0xFF282A31),
    surfaceContainerHigh = Color(0xFF2E3038),
    surfaceContainerHighest = Color(0xFF363941),
)

/**
 * The Day Sheet roles Material 3 has no name for. Read them as `NormPlusTheme.colors`.
 *
 * @property plate the backing ink-blue: the shell and the one reversed plate per screen.
 * @property onPlate text and marks on the plate; [onPlateVariant] for its secondary text.
 * @property plateHairline rules and outlines drawn on the plate.
 * @property sheet the day sheet; [sheetEdge] its perforated hairline and the rules on it.
 * @property sheetBelow the sheets underneath (the edge of yesterday's sheet).
 * @property redLetter calendar red: the goal-met numeral, the "Goal reached" words, a goal
 *   day's bar. Nothing else.
 * @property needsFixing / [onNeedsFixing] / [needsFixingMark] the amber "needs fixing"
 *   container, its text and its icon.
 * @property chartBar a record's bar; [chartGoal] the goal line; [chartAxis] baselines.
 * @property sleepDeep / [sleepLight] / [sleepAwake] the stages, one ink in three tints.
 * @property shellIndicator the selected navigation item's pill on the shell; [onShellIndicator]
 *   its icon.
 */
@Immutable
data class NormColors(
    val plate: Color,
    val onPlate: Color,
    val onPlateVariant: Color,
    val plateHairline: Color,
    val sheet: Color,
    val sheetEdge: Color,
    val sheetBelow: Color,
    val redLetter: Color,
    val needsFixing: Color,
    val onNeedsFixing: Color,
    val needsFixingMark: Color,
    val chartBar: Color,
    val chartGoal: Color,
    val chartAxis: Color,
    val sleepDeep: Color,
    val sleepLight: Color,
    val sleepAwake: Color,
    val shellIndicator: Color,
    val onShellIndicator: Color,
)

internal val LightNormColors = NormColors(
    plate = InkBlue,
    onPlate = SheetWhite,
    onPlateVariant = InkGreyOnPlate,
    plateHairline = Color(0xFF3D4966),
    sheet = SheetWhite,
    sheetEdge = Color(0xFFB4B8C2),
    sheetBelow = Color(0xFFF3F3F1),
    redLetter = CalendarRed,
    needsFixing = Color(0xFFFBE7B5),
    onNeedsFixing = Color(0xFF3A2A00),              // 11.4:1
    needsFixingMark = Color(0xFF8A5A00),            // 4.9:1 on the amber container
    chartBar = InkBlue,
    chartGoal = InkGrey,
    chartAxis = Color(0xFFB4B8C2),
    sleepDeep = InkBlue,
    sleepLight = Color(0xFF7C88A6),
    sleepAwake = Color(0xFFC9CFDC),
    shellIndicator = SheetWhite,
    onShellIndicator = InkBlue,
)

internal val DarkNormColors = NormColors(
    plate = DeepInk,
    onPlate = Color(0xFFE6E9F2),                    // 14.3:1
    onPlateVariant = PaleInkOnPlate,
    plateHairline = Color(0xFF2C3753),
    sheet = NightSheet,
    sheetEdge = Color(0xFF555966),
    sheetBelow = Color(0xFF1C1E24),
    redLetter = NightRed,
    needsFixing = Color(0xFF3F3000),
    onNeedsFixing = Color(0xFFFFE3A3),              // 10.3:1
    needsFixingMark = Color(0xFFF2C14E),            // 7.7:1
    chartBar = PaleInk,
    chartGoal = NightGrey,
    chartAxis = Color(0xFF4A4E59),
    sleepDeep = PaleInk,
    sleepLight = Color(0xFF6F7B99),
    sleepAwake = Color(0xFF3E4352),
    shellIndicator = PaleInk,
    onShellIndicator = Color(0xFF16203A),
)

/**
 * The colour scheme of everything printed on the plate: Material components placed on it
 * (buttons, switches, text) come out reversed without a colour written at the call site.
 * The sheet re-applies the base scheme, so a sheet on the plate prints normally again.
 */
internal fun plateColorScheme(base: ColorScheme, c: NormColors): ColorScheme = base.copy(
    primary = c.onPlate,
    onPrimary = c.plate,
    primaryContainer = c.plateHairline,
    onPrimaryContainer = c.onPlate,
    secondary = c.onPlateVariant,
    onSecondary = c.plate,
    secondaryContainer = c.plateHairline,
    onSecondaryContainer = c.onPlate,
    background = c.plate,
    onBackground = c.onPlate,
    surface = c.plate,
    onSurface = c.onPlate,
    surfaceVariant = c.plateHairline,
    onSurfaceVariant = c.onPlateVariant,
    surfaceTint = c.onPlate,
    outline = c.onPlateVariant,
    outlineVariant = c.plateHairline,
    surfaceContainerLowest = c.plate,
    surfaceContainerLow = c.plate,
    surfaceContainer = c.plate,
    surfaceContainerHigh = c.plateHairline,
    surfaceContainerHighest = c.plateHairline,
    surfaceBright = c.plateHairline,
    surfaceDim = c.plate,
)
