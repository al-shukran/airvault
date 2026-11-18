package app.lensly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The camera cover and the gate keep Lensly's original look: black and grey, white text, a red shutter, Doto and Space Mono type, and
// smaller corners. It is dark whatever the system setting. Everything reachable without the viewfinder gesture takes this theme. The
// hidden area takes LumenTheme.

private val CoverBlack = Color(0xFF000000)
private val CoverSurfaceLow = Color(0xFF0F0F0F)
private val CoverSurface = Color(0xFF141414)
private val CoverSurfaceContainer = Color(0xFF1E1E1E)
private val CoverSurfaceHigh = Color(0xFF2A2A2A)
private val CoverSurfaceHighest = Color(0xFF363636)

/** Decorative rules and frames. It is only 1.85:1 on [CoverBlack], so it never alone marks a control. */
private val CoverOutline = Color(0xFF3A3A3A)

/** The boundary of an interactive control: text field rules, field borders and checkboxes. */
private val CoverOutlineInteractive = Color(0xFF6A6A6A)
private val CoverOnSurface = Color(0xFFFFFFFF)
private val CoverMuted = Color(0xFF8E8E93)
private val CoverAccent = Color(0xFFD71921)
private val CoverWarning = Color(0xFFFFB020)
private val CoverSuccess = Color(0xFF3DDC84)
private val CoverError = Color(0xFFFF6B6B)

private val CoverScheme = darkColorScheme(
    primary = CoverAccent,
    onPrimary = CoverOnSurface,
    secondary = CoverMuted,
    onSecondary = CoverBlack,
    background = CoverBlack,
    onBackground = CoverOnSurface,
    surface = CoverSurface,
    onSurface = CoverOnSurface,
    surfaceDim = CoverBlack,
    surfaceContainerLowest = CoverBlack,
    surfaceContainerLow = CoverSurfaceLow,
    surfaceContainer = CoverSurfaceContainer,
    surfaceContainerHigh = CoverSurfaceHigh,
    surfaceContainerHighest = CoverSurfaceHighest,
    surfaceVariant = CoverSurfaceHigh,
    onSurfaceVariant = CoverMuted,
    secondaryContainer = CoverSurfaceHigh,
    onSecondaryContainer = CoverOnSurface,
    inverseSurface = CoverOnSurface,
    inverseOnSurface = CoverBlack,
    outline = CoverOutlineInteractive,
    outlineVariant = CoverOutline,
    error = CoverError,
    onError = CoverBlack,
)

/** The cover's extended roles: Lensly's own accent, success and warning. The cover shows no testnet strip and no glow. */
private val CoverPalette = LumenPalette(
    accentInk = CoverAccent,
    testnet = CoverMuted,
    positive = CoverSuccess,
    warning = CoverWarning,
    glowCore = Color.Transparent,
    glowEdge = Color.Transparent,
)

private val CoverShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The theme of the camera cover and the gate: Lensly's original dark look, whatever the system setting. The hidden area takes [LumenTheme].
 */
@Composable
fun CoverTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLumenPalette provides CoverPalette,
        LocalUppercaseLabels provides true,
        LocalMonoFamily provides SpaceMonoFamily,
    ) {
        MaterialTheme(
            colorScheme = CoverScheme,
            typography = CoverTypography,
            shapes = CoverShapes,
            content = content,
        )
    }
}

/**
 * Whether the shared components set their labels in capitals. The cover's do. The hidden area's are sentence case, so [LumenTheme] turns this off.
 */
val LocalUppercaseLabels = staticCompositionLocalOf { true }
