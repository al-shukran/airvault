package app.lensly.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast for the dark palette in [Color] and the pairs the UI actually paints.
 *
 * Body text needs 4.5:1; large text and the boundaries of UI components need 3:1. The colours are read from the tokens
 * themselves, so a change to `Color.kt` is checked here without a second copy of the hex values.
 *
 * Pairs that are deliberately not checked, with the measured ratio where it matters:
 *
 * - `LenslyAccent` as small text on `LenslyBlack` (4.05:1) and on `LenslySurfaceContainer` (3.22:1): the brand red is a
 *   fill colour. `WalletWarningChip` and `WalletError` use `LenslyWarning` and `LenslyError` for text instead. This is
 *   the pair the test was written for.
 * - `LenslyOutline` on `LenslyBlack` (1.85:1) and on `LenslySurface` (1.62:1): decorative only. It draws separators,
 *   framed word slots, and the outline on labelled pills and buttons, where the label carries the identity of the
 *   control. It is never the boundary of a control on its own. Every interactive boundary uses
 *   `LenslyOutlineInteractive`, which is checked below at 3:1.
 * - `LenslyMuted` on `LenslySurfaceHigh` (4.40:1) and on `LenslySurfaceHighest` (3.71:1): muted text is never drawn on
 *   the raised fills. Keys and pills there use `LenslyOnSurface`.
 * - `LenslyError` on `LenslySurfaceHighest` (4.35:1): the highest surface is only a dot fill, never behind error text.
 * - `LenslyLogoBackdrop` on `LenslyBlack` (16.83:1): a decorative disc behind the logo, not text.
 * - `LenslyBlack` on `LenslyAccent` (4.05:1): not painted. Accent fills carry `LenslyOnSurface`.
 * - Surface fills against `LenslyBlack` (`LenslySurfaceContainer` 1.29:1, `LenslySurfaceHigh` 1.66:1): panels are told
 *   apart by layout and space, not by a border, so there is no text or component boundary pair here.
 */
class ContrastTest {

    private val bodyText = 4.5
    private val largeTextOrUi = 3.0

    @Test
    fun everyPairMeetsItsWcagThreshold() {
        val failures = pairs.filter { contrastRatio(it.foreground, it.background) < it.minimum }

        val report = buildString {
            appendLine("${failures.size} of ${pairs.size} colour pairs are below their WCAG threshold:")
            for (failure in failures) {
                val ratio = contrastRatio(failure.foreground, failure.background)
                appendLine(
                    "  ${failure.usage}: ${failure.foreground.hex()} on ${failure.background.hex()}" +
                        " = ${"%.2f".format(ratio)}:1, needs ${"%.1f".format(failure.minimum)}:1",
                )
            }
        }

        assertTrue(report, failures.isEmpty())
    }

    @Test
    fun ratioFormulaMatchesKnownValues() {
        // White on black is the maximum: 21:1.
        assertEquals(21.0, contrastRatio(LenslyOnSurface, LenslyBlack), 0.01)
        // A colour against itself is the minimum: 1:1.
        assertEquals(1.0, contrastRatio(LenslySurface, LenslySurface), 0.001)
        // The brand red's measured value on black is why it is a fill colour and not a small-text colour.
        assertEquals(4.05, contrastRatio(LenslyAccent, LenslyBlack), 0.01)
    }

    /**
     * Each row is a pair the UI paints, with the threshold that applies to how it is used. The banner rows use the
     * tinted strip of `InlineBanner`: the tone colour at 12% over the page.
     */
    private val pairs: List<Pair> = listOf(
        // Primary and secondary text.
        Pair(LenslyOnSurface, LenslyBlack, bodyText, "page body and headings"),
        Pair(LenslyOnSurface, LenslySurface, bodyText, "body in a Surface card or dialog"),
        Pair(LenslyOnSurface, LenslySurfaceContainer, bodyText, "body in a panel"),
        Pair(LenslyOnSurface, LenslySurfaceHigh, bodyText, "key and secondary pill labels"),
        Pair(LenslyMuted, LenslyBlack, bodyText, "secondary text on the page"),
        Pair(LenslyMuted, LenslySurface, bodyText, "secondary text in a card or dialog"),
        Pair(LenslyMuted, LenslySurfaceContainer, bodyText, "secondary text in a panel"),

        // Status text.
        Pair(LenslyError, LenslyBlack, bodyText, "error line on the page"),
        Pair(LenslyError, LenslySurface, bodyText, "error label in a dialog"),
        Pair(LenslyError, LenslySurfaceContainer, bodyText, "error text in a restored word field"),
        Pair(LenslyError, LenslySurfaceHigh, bodyText, "error text on a raised fill"),
        Pair(LenslyWarning, LenslyBlack, bodyText, "warning chip label and banner title"),
        Pair(LenslyWarning, LenslySurface, bodyText, "warning title in a card"),
        Pair(LenslyWarning, LenslySurfaceHigh, bodyText, "warning title on a raised fill"),
        Pair(LenslySuccess, LenslyBlack, bodyText, "installed result on the page"),
        Pair(LenslySuccess, LenslySurface, bodyText, "success text in a card"),

        // Filled buttons and keys.
        Pair(LenslyOnSurface, LenslyAccent, bodyText, "accent button and key label"),
        Pair(LenslyBlack, LenslyOnSurface, bodyText, "inverted primary pill and done key"),

        // UI component boundaries and icons.
        Pair(LenslyAccent, LenslyBlack, largeTextOrUi, "accent fill against the page"),
        Pair(LenslyWarning, LenslyBlack, largeTextOrUi, "warning chip border and icon"),

        // Interactive component boundaries: the password rule, the restore and name field borders, the copied
        // checkbox, and the empty gallery button. The container row is the fill the bordered fields sit on, so the
        // border has to stand out from its own inside as well as from the page.
        Pair(LenslyOutlineInteractive, LenslyBlack, largeTextOrUi, "text field and checkbox boundary on the page"),
        Pair(LenslyOutlineInteractive, LenslySurface, largeTextOrUi, "field boundary in a card or dialog"),
        Pair(LenslyOutlineInteractive, LenslySurfaceContainer, largeTextOrUi, "field boundary against the field fill"),

        // InlineBanner: tone text on a 12% tone strip, and the body text on the same strip.
        Pair(LenslyWarning, LenslyWarning.tint(0.12f), bodyText, "warning banner title on its strip"),
        Pair(LenslyOnSurface, LenslyWarning.tint(0.12f), bodyText, "warning banner body on its strip"),
        Pair(LenslyError, LenslyError.tint(0.12f), bodyText, "error banner title on its strip"),
        Pair(LenslyOnSurface, LenslyError.tint(0.12f), bodyText, "error banner body on its strip"),
        Pair(LenslyOnSurface, LenslyOnSurface.tint(0.12f), bodyText, "info banner title and body on its strip"),
    )

    private data class Pair(
        val foreground: Color,
        val background: Color,
        val minimum: Double,
        val usage: String,
    )

    /** The banner strip: [alpha] of [this] drawn over the page. */
    private fun Color.tint(alpha: Float): Color = copy(alpha = alpha).compositeOver(LenslyBlack)

    /** WCAG 2.x relative luminance of an sRGB colour. */
    private fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()

        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG 2.x contrast ratio, always 1:1 or more. */
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
}
