package app.lensly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The Lumen theme, for Lensly's hidden area and for Lumio. The Night palette shows in the dark and the Day palette in the light, as the
 * system chooses. Labels are sentence case, addresses are set in JetBrains Mono, and the status and navigation bar icons follow the palette.
 * The camera cover and the gate keep [CoverTheme].
 */
@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) NightPalette else DayPalette
    SystemBarIcons(lightBackground = !dark)
    CompositionLocalProvider(
        LocalLumenPalette provides palette,
        LocalReducedMotion provides rememberReducedMotion(),
        LocalUppercaseLabels provides false,
        LocalMonoFamily provides LumenMonoFamily,
    ) {
        MaterialTheme(
            colorScheme = if (dark) NightScheme else DayScheme,
            typography = LumenTypography,
            shapes = LumenShapes,
            content = content,
        )
    }
}
