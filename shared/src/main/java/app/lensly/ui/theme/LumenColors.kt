package app.lensly.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Lumen colours, section 1.1 of the Phase 2.8 design. Night is the dark theme and Day the light one. The Day text colours are darker than
// the first values in the spec, so that each one reaches 4.5:1 on every Day surface; LumioColorsTest checks every pair.

/** The Lumen roles that Material's colour scheme has no place for. Read them with [LocalLumenPalette]. */
@Immutable
data class LumenPalette(
    /** The colour of text actions, links and the selected icon. */
    val accentInk: Color,
    /** The testnet strip and tag. It is also the scheme's tertiary role. */
    val testnet: Color,
    /** Received amounts, a rise in price and success. */
    val positive: Color,
    /** Warnings. They always come with an icon, and their hue is apart from the gold. */
    val warning: Color,
    /** The edge of a non-interactive chip: the control edge on the Lumen themes, and the cover's decorative rule on the cover. */
    val chipEdge: Color,
    /** The core of the hero glow. It is drawn at low alpha. */
    val glowCore: Color,
    /** The edge of the hero glow. It is drawn at low alpha. */
    val glowEdge: Color,
)

/** The palette of the theme in composition. [LumenTheme] provides it. */
val LocalLumenPalette = staticCompositionLocalOf<LumenPalette> { error("No theme is in composition: CoverTheme or LumenTheme must wrap it") }

internal val NightPalette = LumenPalette(
    accentInk = Color(0xFFF4C46A),
    testnet = Color(0xFF6FD6E8),
    positive = Color(0xFF4CD69B),
    warning = Color(0xFFFF8A4C),
    chipEdge = Color(0xFF66727F),
    glowCore = Color(0xFFF4C46A).copy(alpha = 0.28f),
    glowEdge = Color(0xFFFF8F70).copy(alpha = 0.12f),
)

internal val DayPalette = LumenPalette(
    accentInk = Color(0xFF7A5200),
    testnet = Color(0xFF006C7C),
    positive = Color(0xFF107144),
    warning = Color(0xFF9C4C00),
    chipEdge = Color(0xFF8A8478),
    glowCore = Color(0xFFF4C46A).copy(alpha = 0.35f),
    glowEdge = Color(0xFFFF8F70).copy(alpha = 0.14f),
)

private val NightBackground = Color(0xFF0A0C10)
private val NightSurface = Color(0xFF0F1217)
private val NightSurfaceLow = Color(0xFF13171D)
private val NightSurfaceContainer = Color(0xFF181D24)
private val NightSurfaceHigh = Color(0xFF202631)
private val NightSurfaceHighest = Color(0xFF2A313D)
private val NightHairline = Color(0xFF262C36)
private val NightControlEdge = Color(0xFF66727F)
private val NightOnSurface = Color(0xFFF2F4F7)
private val NightOnSurfaceVariant = Color(0xFF9AA4B2)
private val NightGold = Color(0xFFF4C46A)
private val NightOnGold = Color(0xFF1C1405)
private val NightNegative = Color(0xFFFF6B7A)

private val DayBackground = Color(0xFFF6F4EF)
private val DaySurface = Color(0xFFFBFAF7)
private val DaySurfaceLow = Color(0xFFF1EEE7)
private val DaySurfaceContainer = Color(0xFFFFFFFF)
private val DaySurfaceHigh = Color(0xFFECE8DF)
private val DaySurfaceHighest = Color(0xFFE3DED3)
private val DayHairline = Color(0xFFE2DDD2)
private val DayControlEdge = Color(0xFF8A8478)
private val DayOnSurface = Color(0xFF15171C)
private val DayOnSurfaceVariant = Color(0xFF5D626E)
private val DayGold = Color(0xFFF0B54D)
private val DayOnGold = Color(0xFF1C1405)
private val DayNegative = Color(0xFFBC2637)

/**
 * The Night scheme. Material's roles map to the Lumen roles of section 1.1. The roles the spec does not name take the surface or text role
 * next to them, so no Material component falls back to a default purple.
 */
internal val NightScheme: ColorScheme = darkColorScheme(
    primary = NightGold,
    onPrimary = NightOnGold,
    primaryContainer = NightSurfaceHigh,
    onPrimaryContainer = NightOnSurface,
    inversePrimary = DayGold,
    secondary = NightOnSurfaceVariant,
    onSecondary = NightBackground,
    secondaryContainer = NightSurfaceHigh,
    onSecondaryContainer = NightOnSurface,
    tertiary = NightPalette.testnet,
    onTertiary = NightBackground,
    tertiaryContainer = NightSurfaceHigh,
    onTertiaryContainer = NightPalette.testnet,
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceTint = NightGold,
    inverseSurface = NightOnSurface,
    inverseOnSurface = NightBackground,
    error = NightNegative,
    onError = NightBackground,
    errorContainer = NightSurfaceHigh,
    onErrorContainer = NightNegative,
    outline = NightControlEdge,
    outlineVariant = NightHairline,
    surfaceDim = NightBackground,
    surfaceBright = NightSurfaceHighest,
    surfaceContainerLowest = NightBackground,
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainer = NightSurfaceContainer,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
)

/** The Day scheme, the light counterpart of [NightScheme] with the same role mapping. */
internal val DayScheme: ColorScheme = lightColorScheme(
    primary = DayGold,
    onPrimary = DayOnGold,
    primaryContainer = DaySurfaceHigh,
    onPrimaryContainer = DayOnSurface,
    inversePrimary = NightGold,
    secondary = DayOnSurfaceVariant,
    onSecondary = DayBackground,
    secondaryContainer = DaySurfaceHigh,
    onSecondaryContainer = DayOnSurface,
    tertiary = DayPalette.testnet,
    onTertiary = DayBackground,
    tertiaryContainer = DaySurfaceHigh,
    onTertiaryContainer = DayPalette.testnet,
    background = DayBackground,
    onBackground = DayOnSurface,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayOnSurfaceVariant,
    surfaceTint = DayGold,
    inverseSurface = DayOnSurface,
    inverseOnSurface = DayBackground,
    error = DayNegative,
    onError = DayBackground,
    errorContainer = DaySurfaceHigh,
    onErrorContainer = DayNegative,
    outline = DayControlEdge,
    outlineVariant = DayHairline,
    surfaceDim = DaySurfaceHighest,
    surfaceBright = DaySurface,
    surfaceContainerLowest = DayBackground,
    surfaceContainerLow = DaySurfaceLow,
    surfaceContainer = DaySurfaceContainer,
    surfaceContainerHigh = DaySurfaceHigh,
    surfaceContainerHighest = DaySurfaceHighest,
)
