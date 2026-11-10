package app.lensly.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LenslyDarkColors = darkColorScheme(
    primary = LenslyAccent,
    onPrimary = LenslyOnSurface,
    secondary = LenslyMuted,
    onSecondary = LenslyBlack,
    background = LenslyBlack,
    onBackground = LenslyOnSurface,
    surface = LenslySurface,
    onSurface = LenslyOnSurface,
    surfaceVariant = LenslySurfaceHigh,
    onSurfaceVariant = LenslyMuted,
    outline = LenslyOutline,
    outlineVariant = LenslyOutline,
    error = LenslyAccent,
    onError = LenslyOnSurface,
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
        content = content,
    )
}
