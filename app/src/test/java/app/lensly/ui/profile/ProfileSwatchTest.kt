package app.lensly.ui.profile

import app.lensly.app.ProfileColour
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The profile palette as a reader sees it: every swatch light enough that the black glyph on it reads, and no two swatches so
 * close that they look the same.
 */
class ProfileSwatchTest {

    /** Every swatch as its three 8-bit channels, in the order the palette stores them. */
    private val channels: List<Triple<Int, Int, Int>> = ProfileColour.entries.map { colour ->
        Triple(colour.red8(), colour.green8(), colour.blue8())
    }

    @Test
    fun everySwatchIsFarEnoughFromTheOthersInRedGreenAndBlue() {
        ProfileColour.entries.forEachIndexed { index, colour ->
            for (other in index + 1 until ProfileColour.entries.size) {
                val a = channels[index]
                val b = channels[other]
                val apart = maxOf(abs(a.first - b.first), abs(a.second - b.second), abs(a.third - b.third))
                assertTrue(
                    apart > 25,
                    "${colour.name} and ${ProfileColour.entries[other].name} are only $apart apart.",
                )
            }
        }
    }

    @Test
    fun everySwatchReadsAsADarkThemeAccent() {
        ProfileColour.entries.forEach { colour ->
            val contrast = (relativeLuminance(colour) + 0.05) / 0.05
            assertTrue(contrast >= 4.5, "${colour.name} has only $contrast against black.")
        }
    }

    @Test
    fun thePaletteIsTwiceTheEightColoursItStartedWith() {
        assertEquals(16, ProfileColour.entries.size)
    }

    /** The relative luminance of a swatch, as WCAG defines it, so its contrast against black is that plus 0.05 over 0.05. */
    private fun relativeLuminance(colour: ProfileColour): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(colour.red8()) + 0.7152 * channel(colour.green8()) + 0.0722 * channel(colour.blue8())
    }

    private fun ProfileColour.red8(): Int = (swatch().red * 255f).roundToInt()
    private fun ProfileColour.green8(): Int = (swatch().green * 255f).roundToInt()
    private fun ProfileColour.blue8(): Int = (swatch().blue * 255f).roundToInt()
}
