package app.lumio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.lensly.ui.theme.LocalMonoFamily
import app.lensly.ui.theme.LocalUppercaseLabels
import app.lensly.ui.theme.LocalWarningColor

/**
 * Lumio's theme. The Night palette shows in the dark and the Day palette in the light, as the system chooses. Labels are sentence case, and
 * addresses are set in JetBrains Mono. The Lensly cover and gate keep Lensly's own theme.
 */
@Composable
fun LumioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) NightPalette else DayPalette
    CompositionLocalProvider(
        LocalLumioPalette provides palette,
        LocalReducedMotion provides rememberReducedMotion(),
        LocalUppercaseLabels provides false,
        LocalWarningColor provides palette.warning,
        LocalMonoFamily provides LumioMonoFamily,
    ) {
        MaterialTheme(
            colorScheme = if (dark) NightScheme else DayScheme,
            typography = LumioTypography,
            shapes = LumioShapes,
            content = content,
        )
    }
}
