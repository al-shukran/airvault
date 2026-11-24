package app.lensly.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Clear Air colours, from the Visual System of the Clear Air proposal (21 Nov 2026). Paper is the light theme and Ink the dark one. Colour is
// rationed: it means a state (blue settled, amber waiting, rose failed). The one filled use of rose is a destructive capsule, which the locked
// mocks draw rose-filled, such as Reset Lumio and removing a wallet. ClearAirColorsTest checks the contrast of the pairs the proposal lists.

/** The Clear Air roles that Material's colour scheme has no place for. Read them with [LocalClearAirPalette]. */
@Immutable
data class ClearAirPalette(
    /** The page: Paper in the light theme, and Night Ink (layer 0) in the dark one. */
    val background: Color,
    /** The first sheet over the page: Sheet in the light theme, and layer 1 in the dark one. */
    val sheet: Color,
    /** Fields and logo discs in the light theme, and the raised surface (layer 2) in the dark one. */
    val raised: Color,
    /** Dividers only. */
    val hairline: Color,
    /** Text in both themes, and the fill of the primary action in the light theme. */
    val ink: Color,
    /** Secondary text. */
    val ink2: Color,
    /** Glyphs and control edges. It is not text. */
    val ink3: Color,
    /** The fill of the primary capsule. */
    val primary: Color,
    /** The label of the primary capsule. */
    val onPrimary: Color,
    /** The spark: the fill of focus, the step and the handoff. On paper it never carries text, so it always comes with a second cue. */
    val spark: Color,
    /** The spark as text, which is the waiting status. */
    val sparkText: Color,
    /** Gains, settled and done. */
    val vaultBlue: Color,
    /** Losses and errors. */
    val rose: Color,
    /** The hatch lines of the testnet band. A texture, not a colour. */
    val testHatch: Color,
    /** Paper in both themes: the scan brackets and their caption, which sit on the dark camera. */
    val paper: Color,
)

/** The palette of the theme in composition. [ClearAirTheme] provides it. */
val LocalClearAirPalette = staticCompositionLocalOf<ClearAirPalette> { error("No theme is in composition: ClearAirTheme must wrap it") }

internal val ClearAirPaperPalette = ClearAirPalette(
    background = Color(0xFFFBF6EC),
    sheet = Color(0xFFFFFDF8),
    raised = Color(0xFFF2ECE0),
    hairline = Color(0xFFE6DDCC),
    ink = Color(0xFF2B2530),
    ink2 = Color(0xFF665E6B),
    ink3 = Color(0xFF8E8692),
    primary = Color(0xFF2B2530),
    onPrimary = Color(0xFFFBF6EC),
    spark = Color(0xFFF2A14A),
    sparkText = Color(0xFF94500C),
    vaultBlue = Color(0xFF2A56A8),
    rose = Color(0xFFB3261E),
    testHatch = Color(0xFF2B2530).copy(alpha = 0.08f),
    paper = Color(0xFFFBF6EC),
)

internal val ClearAirInkPalette = ClearAirPalette(
    background = Color(0xFF151317),
    sheet = Color(0xFF1F1C22),
    raised = Color(0xFF2B2530),
    hairline = Color(0xFF36313B),
    ink = Color(0xFFF5EFE4),
    ink2 = Color(0xFFABA3AE),
    ink3 = Color(0xFF8A828D),
    primary = Color(0xFFFBF6EC),
    onPrimary = Color(0xFF2B2530),
    spark = Color(0xFFF2A14A),
    sparkText = Color(0xFFF2A14A),
    vaultBlue = Color(0xFF8DBBFF),
    rose = Color(0xFFFF8A80),
    testHatch = Color(0xFFF5EFE4).copy(alpha = 0.075f),
    paper = Color(0xFFFBF6EC),
)

internal val ClearAirPaperScheme: ColorScheme = clearAirScheme(ClearAirPaperPalette, lightColorScheme())
internal val ClearAirInkScheme: ColorScheme = clearAirScheme(ClearAirInkPalette, darkColorScheme())

/** Material's roles take the Clear Air roles, so no Material component falls back to its default colours. */
private fun clearAirScheme(palette: ClearAirPalette, base: ColorScheme): ColorScheme = base.copy(
    primary = palette.primary,
    onPrimary = palette.onPrimary,
    primaryContainer = palette.raised,
    onPrimaryContainer = palette.ink,
    inversePrimary = palette.ink,
    secondary = palette.ink2,
    onSecondary = palette.background,
    secondaryContainer = palette.raised,
    onSecondaryContainer = palette.ink,
    tertiary = palette.spark,
    onTertiary = palette.background,
    tertiaryContainer = palette.raised,
    onTertiaryContainer = palette.sparkText,
    background = palette.background,
    onBackground = palette.ink,
    surface = palette.sheet,
    onSurface = palette.ink,
    surfaceVariant = palette.raised,
    onSurfaceVariant = palette.ink2,
    surfaceTint = palette.ink,
    inverseSurface = palette.ink,
    inverseOnSurface = palette.background,
    error = palette.rose,
    onError = palette.background,
    errorContainer = palette.raised,
    onErrorContainer = palette.rose,
    outline = palette.ink3,
    outlineVariant = palette.hairline,
    surfaceDim = palette.background,
    surfaceBright = palette.sheet,
    surfaceContainerLowest = palette.background,
    surfaceContainerLow = palette.sheet,
    surfaceContainer = palette.sheet,
    surfaceContainerHigh = palette.raised,
    surfaceContainerHighest = palette.raised,
)
