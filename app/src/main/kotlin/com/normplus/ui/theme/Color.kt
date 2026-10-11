package com.normplus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * The Dark Dial palette (#92, #96): a dark room with the watch in it. Every colour has a job.
 *
 *  - Ground: near-black, with ONE soft violet glow behind each screen's hero (Glow.kt).
 *  - Cards on it, a hairline round each; "raised" for pill buttons and the capsule.
 *  - Signal violet: selection, actions, progress, and the dial's ring. Nothing decorative.
 *  - Green: "fine" (the status pill) and "goal reached". Amber: something to fix. Red: an
 *    error. A state colour always comes with an icon and words, never alone.
 *
 * Light is designed, not inverted: a pale ground with white cards, the violet deepened until
 * it passes 4.5:1 on both, and the state colours darkened for text.
 *
 * Contrast (WCAG 2.x) of every text pair is noted beside the colour it was chosen for, as
 * "on <background> N:1". Body text needs 4.5:1, control boundaries 3:1.
 *
 * Screens never use these constants: they read MaterialTheme.colorScheme and
 * NormPlusTheme.colors. They are internal to the theme.
 */

// ── Dark (the first scheme) ───────────────────────────────────────────────────
internal val Ground = Color(0xFF0B0C0F)
internal val Card = Color(0xFF17181C)
internal val CardHairline = Color(0xFF24262C)
internal val Raised = Color(0xFF1F2126)
internal val Ink = Color(0xFFF4F5F7)                // on ground 17.9:1, on card 16.3:1, on raised 14.8:1
internal val InkSecondary = Color(0xFFA3A7B0)       // on ground 8.1:1, on card 7.4:1, on raised 6.7:1
internal val Violet = Color(0xFFA68BFF)             // on ground 7.2:1, on card 6.6:1, on raised 6.0:1
internal val OnViolet = Color(0xFF1A1033)           // on violet 6.7:1
internal val VioletContainer = Color(0xFF2A2147)    // violet on it 5.5:1
internal val OnVioletContainer = Color(0xFFDCD2FF)  // on the container 10.5:1
internal val Green = Color(0xFF5BD98A)              // on its container 8.0:1, on card 9.9:1
internal val GreenContainer = Color(0xFF12301F)
internal val Amber = Color(0xFFFFB547)              // on card 10.1:1, on its container 8.4:1
internal val AmberContainer = Color(0xFF33260C)
internal val OnAmberContainer = Color(0xFFFFD99A)   // 11.0:1
internal val OnAmber = Color(0xFF1F1400)            // on amber 10.3:1
internal val Red = Color(0xFFFF6B6B)                // on card 6.4:1, on its container 5.8:1
internal val RedContainer = Color(0xFF3A1517)
internal val OnRedContainer = Color(0xFFFFB4AE)     // 9.5:1
internal val OnRed = Color(0xFF2B0606)              // on red 6.7:1
internal val OutlineDark = Color(0xFF6B6F78)        // control boundaries: on card 3.5:1, on ground 3.9:1

// ── Light ─────────────────────────────────────────────────────────────────────
internal val PaleGround = Color(0xFFF2F2F5)
internal val White = Color(0xFFFFFFFF)
internal val PaleHairline = Color(0xFFE3E3E8)
internal val PaleRaised = Color(0xFFE9E9EE)
internal val InkDark = Color(0xFF14161B)            // on ground 16.2:1, on white 18.1:1, on raised 14.8:1
internal val InkDarkSecondary = Color(0xFF5C606B)   // on white 6.3:1, on ground 5.6:1, on raised 5.2:1
internal val DeepViolet = Color(0xFF6246E0)         // on white 6.1:1, on ground 5.4:1, on raised 5.0:1; white on it 6.1:1
internal val PaleVioletContainer = Color(0xFFECE6FF) // deep violet on it 5.0:1
internal val OnPaleVioletContainer = Color(0xFF2A1873) // 11.7:1
internal val DeepGreen = Color(0xFF16753E)          // on white 5.8:1, on ground 5.2:1, on its container 5.0:1
internal val PaleGreenContainer = Color(0xFFDDF5E6)
internal val BrightAmber = Color(0xFFF5A623)        // the banner's fill: its words 8.3:1
internal val DeepAmber = Color(0xFFA15C00)          // icons: on white 5.2:1, on its container 4.6:1
internal val PaleAmberContainer = Color(0xFFFFEFD1)
internal val OnPaleAmberContainer = Color(0xFF5A3A00) // 9.1:1
internal val OnBrightAmber = Color(0xFF2A1A00)
internal val DeepRed = Color(0xFFB3261E)            // on white 6.5:1, on its container 5.4:1
internal val BannerRed = Color(0xFFC62828)          // the banner's fill: white on it 5.6:1
internal val PaleRedContainer = Color(0xFFFCE4E2)
internal val OnPaleRedContainer = Color(0xFF5C0A0A) // 11.5:1
internal val OutlineLight = Color(0xFF8A8E99)       // control boundaries: on white 3.3:1

/** The watch's own display: a black AMOLED, the same in both schemes, as the object is. */
internal val DisplayBlack = Color(0xFF000000)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Violet,
    onPrimary = OnViolet,
    primaryContainer = VioletContainer,
    onPrimaryContainer = OnVioletContainer,
    inversePrimary = DeepViolet,
    // Selection reads violet everywhere: chips, segmented buttons and the navigation pill
    // take secondaryContainer.
    secondary = InkSecondary,
    onSecondary = Ground,
    secondaryContainer = VioletContainer,
    onSecondaryContainer = OnVioletContainer,
    // Tertiary is the "fine" green, so nothing Material draws by default borrows a new hue.
    tertiary = Green,
    onTertiary = GreenContainer,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = Green,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = Raised,
    onSurfaceVariant = InkSecondary,
    surfaceTint = Violet,
    inverseSurface = Ink,                           // snackbars: dark words on light, 16.3:1
    inverseOnSurface = Card,
    error = Red,
    onError = OnRed,
    errorContainer = RedContainer,
    onErrorContainer = OnRedContainer,
    outline = OutlineDark,
    outlineVariant = CardHairline,
    scrim = DisplayBlack,
    surfaceBright = Color(0xFF2C2E35),
    surfaceDim = Ground,
    surfaceContainerLowest = Color(0xFF070809),
    surfaceContainerLow = Color(0xFF121317),
    surfaceContainer = Card,                        // cards
    surfaceContainerHigh = Raised,                  // dialogs, menus, pill buttons, the capsule
    surfaceContainerHighest = Color(0xFF2A2C33),    // switch tracks
)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = DeepViolet,
    onPrimary = White,
    primaryContainer = PaleVioletContainer,
    onPrimaryContainer = OnPaleVioletContainer,
    inversePrimary = Violet,
    secondary = InkDarkSecondary,
    onSecondary = White,
    secondaryContainer = PaleVioletContainer,
    onSecondaryContainer = OnPaleVioletContainer,
    tertiary = DeepGreen,
    onTertiary = White,
    tertiaryContainer = PaleGreenContainer,
    onTertiaryContainer = DeepGreen,
    background = PaleGround,
    onBackground = InkDark,
    surface = PaleGround,
    onSurface = InkDark,
    surfaceVariant = PaleRaised,
    onSurfaceVariant = InkDarkSecondary,
    surfaceTint = DeepViolet,
    inverseSurface = Color(0xFF2A2C33),
    inverseOnSurface = Ink,                         // 13.0:1
    error = DeepRed,
    onError = White,
    errorContainer = PaleRedContainer,
    onErrorContainer = OnPaleRedContainer,
    outline = OutlineLight,
    outlineVariant = PaleHairline,
    scrim = DisplayBlack,
    surfaceBright = White,
    surfaceDim = Color(0xFFE4E4E9),
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = White,                       // cards
    surfaceContainerHigh = White,                   // dialogs and menus: white on the pale ground
    surfaceContainerHighest = Color(0xFFE1E1E7),    // switch tracks
)

/**
 * The Dark Dial roles Material 3 has no name for. Read them as `NormPlusTheme.colors`.
 *
 * @property card a rounded card on the ground; [cardHairline] the hairline round it.
 * @property raised pill buttons, the navigation capsule, a quiet banner.
 * @property glow the violet of the one glow behind a screen's hero, at its brightest
 *   ([glowAlpha] at the centre, fading to nothing).
 * @property fine / [onFine] / [fineContainer] green: the watch is fine, a goal is reached.
 * @property fix / [fixContainer] / [onFixContainer] amber: a fix-it's icon, its tinted card
 *   and the card's words. [fixBanner] / [onFixBanner] the full-width banner's fill and words.
 * @property failBanner / [onFailBanner] the error banner's fill and words (the tinted error
 *   card uses MaterialTheme's errorContainer).
 * @property chartBar a record's bar; [chartGoalMet] a goal day's bar; [chartGoal] the goal
 *   line and its label; [chartAxis] baselines and guides.
 * @property sleepDeep / [sleepLight] / [sleepAwake] the stages: two violets and a grey.
 * @property dialDisplay the watch's black display; [dialBezel] the bezel ring round it;
 *   [dialRing] the violet ring outside the bezel; [dialHand] the physical hands;
 *   [dialInk] / [dialInkSecondary] words drawn on the display; [dialAccent] icons on it.
 *   The display is a black AMOLED in both schemes, so everything drawn on it is fixed.
 */
@Immutable
data class NormColors(
    val card: Color,
    val cardHairline: Color,
    val raised: Color,
    val glow: Color,
    val glowAlpha: Float,
    val fine: Color,
    val onFine: Color,
    val fineContainer: Color,
    val fix: Color,
    val fixContainer: Color,
    val onFixContainer: Color,
    val fixBanner: Color,
    val onFixBanner: Color,
    val failBanner: Color,
    val onFailBanner: Color,
    val chartBar: Color,
    val chartGoalMet: Color,
    val chartGoal: Color,
    val chartAxis: Color,
    val sleepDeep: Color,
    val sleepLight: Color,
    val sleepAwake: Color,
    val dialDisplay: Color,
    val dialBezel: Color,
    val dialBezelEdge: Color,
    val dialRing: Color,
    val dialHand: Color,
    val dialInk: Color,
    val dialInkSecondary: Color,
    val dialAccent: Color,
)

internal val DarkNormColors = NormColors(
    card = Card,
    cardHairline = CardHairline,
    raised = Raised,
    glow = Color(0xFF7B5CFF),
    glowAlpha = 0.30f,
    fine = Green,
    onFine = GreenContainer,
    fineContainer = GreenContainer,
    fix = Amber,
    fixContainer = AmberContainer,
    onFixContainer = OnAmberContainer,
    fixBanner = Amber,
    onFixBanner = OnAmber,
    failBanner = Red,
    onFailBanner = OnRed,
    chartBar = Violet,
    chartGoalMet = Green,
    chartGoal = InkSecondary,
    chartAxis = Color(0xFF34363D),
    sleepDeep = Color(0xFF7C5CF0),                  // on card 3.9:1
    sleepLight = Color(0xFFC3B3FF),                 // 9.5:1
    sleepAwake = OutlineDark,                       // 3.5:1
    dialDisplay = DisplayBlack,
    dialBezel = Color(0xFF3A3C42),
    dialBezelEdge = Color(0xFF55585F),
    dialRing = Violet,
    dialHand = Color(0xFFE9EAEE),
    dialInk = Ink,
    dialInkSecondary = InkSecondary,
    dialAccent = Violet,
)

internal val LightNormColors = NormColors(
    card = White,
    cardHairline = PaleHairline,
    raised = PaleRaised,
    glow = Color(0xFF8A6CFF),
    glowAlpha = 0.20f,
    fine = DeepGreen,
    onFine = White,
    fineContainer = PaleGreenContainer,
    fix = DeepAmber,
    fixContainer = PaleAmberContainer,
    onFixContainer = OnPaleAmberContainer,
    fixBanner = BrightAmber,
    onFixBanner = OnBrightAmber,
    failBanner = BannerRed,
    onFailBanner = White,
    chartBar = DeepViolet,
    chartGoalMet = DeepGreen,
    chartGoal = InkDarkSecondary,
    chartAxis = Color(0xFFD5D6DC),
    sleepDeep = Color(0xFF4F33C2),                  // on white 8.1:1
    sleepLight = Color(0xFF8C74F2),                 // 3.6:1
    sleepAwake = OutlineLight,                      // 3.3:1
    // The watch is the same object in daylight: only its ring takes the light scheme's violet.
    dialDisplay = DisplayBlack,
    dialBezel = Color(0xFF2E3036),
    dialBezelEdge = Color(0xFF4A4D55),
    dialRing = DeepViolet,
    dialHand = Color(0xFFE9EAEE),
    dialInk = Ink,
    dialInkSecondary = InkSecondary,
    dialAccent = Violet,
)
