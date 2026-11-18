package app.lensly.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

private val LenslyDarkColors = darkColorScheme(
    primary = LenslyAccent,
    onPrimary = LenslyOnSurface,
    secondary = LenslyMuted,
    onSecondary = LenslyBlack,
    background = LenslyBlack,
    onBackground = LenslyOnSurface,
    surface = LenslySurface,
    onSurface = LenslyOnSurface,
    surfaceDim = LenslyBlack,
    surfaceContainerLowest = LenslyBlack,
    surfaceContainerLow = LenslySurfaceLow,
    surfaceContainer = LenslySurfaceContainer,
    surfaceContainerHigh = LenslySurfaceHigh,
    surfaceContainerHighest = LenslySurfaceHighest,
    surfaceVariant = LenslySurfaceHigh,
    onSurfaceVariant = LenslyMuted,
    secondaryContainer = LenslySurfaceHigh,
    onSecondaryContainer = LenslyOnSurface,
    outline = LenslyOutline,
    outlineVariant = LenslyOutline,
    error = LenslyError,
    onError = LenslyBlack,
)

/**
 * Lensly's only theme. There is no light scheme; the dark palette is applied
 * regardless of the system setting.
 */
@Composable
fun LenslyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LenslyDarkColors,
        typography = LenslyTypography,
        shapes = LenslyShapes,
        content = content,
    )
}

/**
 * Whether the shared components set their labels in capitals. Lensly's do. Lumio's are sentence case, so its theme turns this off.
 */
val LocalUppercaseLabels = staticCompositionLocalOf { true }

/**
 * The warning colour of the shared banners. Lumio's theme sets its own, so a warning stays legible on its light surfaces.
 */
val LocalWarningColor = staticCompositionLocalOf { LenslyWarning }
