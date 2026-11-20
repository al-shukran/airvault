package app.lensly.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Every text colour of the Lumen palettes reads at 4.5:1 or better on each surface of its scheme, and the Lumen ring reads at 3:1 or better
 * (a UI component), by the WCAG 2.x contrast ratio.
 */
class LumioColorsTest {

    @Test
    fun nightTextReadsOnEverySurface() = assertTextPairs(NightScheme, NightPalette)

    @Test
    fun dayTextReadsOnEverySurface() = assertTextPairs(DayScheme, DayPalette)

    @Test
    fun nightRingReadsOnEverySurface() = assertRingPairs(NightScheme, NightPalette)

    @Test
    fun dayRingReadsOnEverySurface() = assertRingPairs(DayScheme, DayPalette)

    private fun assertTextPairs(scheme: ColorScheme, palette: LumenPalette) {
        val texts = mapOf(
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "accentInk" to palette.accentInk,
            "testnet" to palette.testnet,
            "positive" to palette.positive,
            "negative" to scheme.error,
            "warning" to palette.warning,
            "info" to palette.info,
        )
        for ((textName, text) in texts) {
            for ((surfaceName, surface) in surfacesOf(scheme)) {
                val ratio = contrast(text, surface)
                assertTrue(ratio >= MIN_TEXT_CONTRAST, "$textName on $surfaceName is ${"%.2f".format(ratio)}:1, below 4.5:1")
            }
        }
        // The primary button's label sits on its gold fill, not on a surface.
        val onGold = contrast(scheme.onPrimary, scheme.primary)
        assertTrue(onGold >= MIN_TEXT_CONTRAST, "onPrimary on primary is ${"%.2f".format(onGold)}:1, below 4.5:1")
    }

    /** The ring is drawn on the page and on every surface above it, so it is checked on each one. */
    private fun assertRingPairs(scheme: ColorScheme, palette: LumenPalette) {
        for ((surfaceName, surface) in surfacesOf(scheme)) {
            val ratio = contrast(palette.ring, surface)
            assertTrue(ratio >= MIN_UI_CONTRAST, "ring on $surfaceName is ${"%.2f".format(ratio)}:1, below 3:1")
        }
    }

    private fun surfacesOf(scheme: ColorScheme): Map<String, Color> = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    )

    /** The WCAG 2.x contrast ratio of two colours, from 1 to 21. */
    private fun contrast(a: Color, b: Color): Double {
        val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
        val darker = minOf(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** The relative luminance of an sRGB colour, as WCAG 2.x defines it. */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
        const val MIN_UI_CONTRAST = 3.0
    }
}
