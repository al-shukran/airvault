package app.lensly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The Clear Air theme of Lumio. Paper shows in the light theme and Ink in the dark one, as the system chooses. Lensly keeps [LumenTheme], so
 * this theme is only ever wrapped around Lumio's screens.
 */
@Composable
fun ClearAirTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalClearAirPalette provides if (dark) ClearAirInkPalette else ClearAirPaperPalette,
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = if (dark) ClearAirInkScheme else ClearAirPaperScheme,
            typography = ClearAirTypography,
            shapes = ClearAirShapes,
            content = content,
        )
    }
}
