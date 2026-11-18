package app.lensly.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast for the camera cover's dark palette, [CoverScheme] and [CoverPalette], and the pairs the UI actually paints.
 *
 * Body text needs 4.5:1; large text and the boundaries of UI components need 3:1. The colours are read from the palette itself,
 * so a change to `Theme.kt` is checked here without a second copy of the hex values.
 *
 * Pairs that are deliberately not checked, with the measured ratio where it matters:
 *
 * - `CoverScheme.primary` as small text on `CoverScheme.background` (4.05:1) and on `CoverScheme.surfaceContainer` (3.22:1): the brand red is a
 *   fill colour. `WalletWarningChip` and `WalletError` use `CoverPalette.warning` and `CoverScheme.error` for text instead. This is
 *   the pair the test was written for.
 * - `CoverScheme.outlineVariant` on `CoverScheme.background` (1.85:1) and on `CoverScheme.surface` (1.62:1): decorative only. It draws separators,
 *   framed word slots, and the outline on labelled pills and buttons, where the label carries the identity of the
 *   control. It is never the boundary of a control on its own. Every interactive boundary uses
 *   `CoverScheme.outline`, which is checked below at 3:1.
 * - `CoverScheme.onSurfaceVariant` on `CoverScheme.surfaceContainerHigh` (4.40:1) and on `CoverScheme.surfaceContainerHighest` (3.71:1): muted text is never drawn on
 *   the raised fills. Keys and pills there use `CoverScheme.onSurface`.
 * - `CoverScheme.error` on `CoverScheme.surfaceContainerHighest` (4.35:1): the highest surface is only a dot fill, never behind error text.
 * - `LenslyLogoBackdrop` on `CoverScheme.background` (16.83:1): a decorative disc behind the logo, not text.
 * - `CoverScheme.background` on `CoverScheme.primary` (4.05:1): not painted. Accent fills carry `CoverScheme.onSurface`.
 * - Surface fills against `CoverScheme.background` (`CoverScheme.surfaceContainer` 1.29:1, `CoverScheme.surfaceContainerHigh` 1.66:1): panels are told
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
        assertEquals(21.0, contrastRatio(CoverScheme.onSurface, CoverScheme.background), 0.01)
        // A colour against itself is the minimum: 1:1.
        assertEquals(1.0, contrastRatio(CoverScheme.surface, CoverScheme.surface), 0.001)
        // The brand red's measured value on black is why it is a fill colour and not a small-text colour.
        assertEquals(4.05, contrastRatio(CoverScheme.primary, CoverScheme.background), 0.01)
    }

    /**
     * Each row is a pair the UI paints, with the threshold that applies to how it is used. The banner rows use the
     * tinted strip of `InlineBanner`: the tone colour at 12% over the page.
     */
    private val pairs: List<Pair> = listOf(
        // Primary and secondary text.
        Pair(CoverScheme.onSurface, CoverScheme.background, bodyText, "page body and headings"),
        Pair(CoverScheme.onSurface, CoverScheme.surface, bodyText, "body in a Surface card or dialog"),
        Pair(CoverScheme.onSurface, CoverScheme.surfaceContainer, bodyText, "body in a panel"),
        Pair(CoverScheme.onSurface, CoverScheme.surfaceContainerHigh, bodyText, "key and secondary pill labels"),
        Pair(CoverScheme.onSurfaceVariant, CoverScheme.background, bodyText, "secondary text on the page"),
        Pair(CoverScheme.onSurfaceVariant, CoverScheme.surface, bodyText, "secondary text in a card or dialog"),
        Pair(CoverScheme.onSurfaceVariant, CoverScheme.surfaceContainer, bodyText, "secondary text in a panel"),

        // Status text.
        Pair(CoverScheme.error, CoverScheme.background, bodyText, "error line on the page"),
        Pair(CoverScheme.error, CoverScheme.surface, bodyText, "error label in a dialog"),
        Pair(CoverScheme.error, CoverScheme.surfaceContainer, bodyText, "error text in a restored word field"),
        Pair(CoverScheme.error, CoverScheme.surfaceContainerHigh, bodyText, "error text on a raised fill"),
        Pair(CoverPalette.warning, CoverScheme.background, bodyText, "warning chip label and banner title"),
        Pair(CoverPalette.warning, CoverScheme.surface, bodyText, "warning title in a card"),
        Pair(CoverPalette.warning, CoverScheme.surfaceContainerHigh, bodyText, "warning title on a raised fill"),
        Pair(CoverPalette.positive, CoverScheme.background, bodyText, "installed result on the page"),
        Pair(CoverPalette.positive, CoverScheme.surface, bodyText, "success text in a card"),

        // Filled buttons and keys.
        Pair(CoverScheme.onSurface, CoverScheme.primary, bodyText, "accent button and key label"),
        Pair(CoverScheme.background, CoverScheme.onSurface, bodyText, "inverted primary pill and done key"),

        // UI component boundaries and icons.
        Pair(CoverScheme.primary, CoverScheme.background, largeTextOrUi, "accent fill against the page"),
        Pair(CoverPalette.warning, CoverScheme.background, largeTextOrUi, "warning chip border and icon"),

        // Interactive component boundaries: the password rule, the restore and name field borders, the copied
        // checkbox, and the empty gallery button. The container row is the fill the bordered fields sit on, so the
        // border has to stand out from its own inside as well as from the page.
        Pair(CoverScheme.outline, CoverScheme.background, largeTextOrUi, "text field and checkbox boundary on the page"),
        Pair(CoverScheme.outline, CoverScheme.surface, largeTextOrUi, "field boundary in a card or dialog"),
        Pair(CoverScheme.outline, CoverScheme.surfaceContainer, largeTextOrUi, "field boundary against the field fill"),

        // InlineBanner: tone text on a 12% tone strip, and the body text on the same strip.
        Pair(CoverPalette.warning, CoverPalette.warning.tint(0.12f), bodyText, "warning banner title on its strip"),
        Pair(CoverScheme.onSurface, CoverPalette.warning.tint(0.12f), bodyText, "warning banner body on its strip"),
        Pair(CoverScheme.error, CoverScheme.error.tint(0.12f), bodyText, "error banner title on its strip"),
        Pair(CoverScheme.onSurface, CoverScheme.error.tint(0.12f), bodyText, "error banner body on its strip"),
        Pair(CoverScheme.onSurface, CoverScheme.onSurface.tint(0.12f), bodyText, "info banner title and body on its strip"),
    )

    private data class Pair(
        val foreground: Color,
        val background: Color,
        val minimum: Double,
        val usage: String,
    )

    /** The banner strip: [alpha] of [this] drawn over the page. */
    private fun Color.tint(alpha: Float): Color = copy(alpha = alpha).compositeOver(CoverScheme.background)

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
