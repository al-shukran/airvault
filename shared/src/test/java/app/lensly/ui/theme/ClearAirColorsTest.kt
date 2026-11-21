package app.lensly.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Clear Air palettes, by the WCAG 2.x contrast ratio. Text reads at 4.5:1 or better on each surface it sits on. Glyphs and control edges read at
 * 3:1 or better. The spark fill is not checked: on paper it is below 3:1, so it always comes with a second cue, as the proposal says.
 *
 * The glyph pairs are the ones the proposal lists. Ink 3 on the light Sunk field reads at 2.99:1, which is below 3:1, and the proposal does not
 * list that pair, so ink 3 is not a control edge on a light field.
 */
class ClearAirColorsTest {

    @Test
    fun paperTextReadsOnEverySurface() = assertText(ClearAirPaperPalette)

    @Test
    fun inkTextReadsOnEverySurface() = assertText(ClearAirInkPalette)

    @Test
    fun paperGlyphsAndEdgesRead() = assertGlyphs(ClearAirPaperPalette)

    @Test
    fun inkGlyphsAndEdgesRead() = assertGlyphs(ClearAirInkPalette)

    @Test
    fun paperPrimaryLabelReads() = assertTrue(contrast(ClearAirPaperPalette.onPrimary, ClearAirPaperPalette.primary) >= MIN_TEXT_CONTRAST)

    @Test
    fun inkPrimaryLabelReads() = assertTrue(contrast(ClearAirInkPalette.onPrimary, ClearAirInkPalette.primary) >= MIN_TEXT_CONTRAST)

    private fun assertText(palette: ClearAirPalette) {
        val texts = mapOf(
            "ink" to palette.ink,
            "ink2" to palette.ink2,
            "sparkText" to palette.sparkText,
            "vaultBlue" to palette.vaultBlue,
            "rose" to palette.rose,
        )
        val surfaces = mapOf("background" to palette.background, "sheet" to palette.sheet, "raised" to palette.raised)
        for ((textName, text) in texts) {
            for ((surfaceName, surface) in surfaces) {
                val ratio = contrast(text, surface)
                assertTrue("$textName on $surfaceName is ${"%.2f".format(ratio)}:1, needs 4.5:1", ratio >= MIN_TEXT_CONTRAST)
            }
        }
    }

    private fun assertGlyphs(palette: ClearAirPalette) {
        val pairs = mapOf(
            "ink3 on background" to contrast(palette.ink3, palette.background),
            "primary on background" to contrast(palette.primary, palette.background),
        )
        for ((name, ratio) in pairs) {
            assertTrue("$name is ${"%.2f".format(ratio)}:1, needs 3:1", ratio >= MIN_UI_CONTRAST)
        }
    }

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
