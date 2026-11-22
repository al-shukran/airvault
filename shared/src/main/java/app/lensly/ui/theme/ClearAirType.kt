package app.lensly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.shared.R

// The three faces the owner kept: Archivo at width 114 for headings, Archivo at width 92 for body text, and JetBrains Mono for numbers. The numbers
// use the same variable file as the Lumen theme, at weight 300 for the big figures.

@OptIn(ExperimentalTextApi::class)
private fun archivo(width: Float) = FontFamily(
    Font(R.font.archivo_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(width))),
    Font(R.font.archivo_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.width(width))),
    Font(R.font.archivo_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(width))),
)

private val HeadingFamily = archivo(width = 114f)
private val BodyFamily = archivo(width = 92f)

// Every figure of JetBrains Mono has the same width, so an amount never moves the text around it.
@OptIn(ExperimentalTextApi::class)
private val NumberFamily = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.jetbrains_mono_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** A size and a line height, in sp. */
private class ClearAirStep(val size: Float, val line: Float)

/**
 * Every size of the Clear Air type scale, in sp, in one place. [ClearAirType] builds each style from one of these steps, so a revised scale is a
 * change to these lines and nothing else. The sizes are the designer's revision 2, except the hero: it stays at 40 sp, provisionally, until the
 * designer confirms the size of the tab heroes.
 */
private object ClearAirScale {
    /** The hero numeral of an overview, Home and Portfolio. Provisional: revision 2 has 50 sp. */
    val hero = ClearAirStep(size = 40f, line = 46f)

    /** A page total, of a wallet or an account. It is left-aligned, under a small label. */
    val total = ClearAirStep(size = 28f, line = 34f)

    /** An amount in a flow, "Amount L". */
    val amountLarge = ClearAirStep(size = 38f, line = 44f)

    /** The question of a screen. */
    val titleXL = ClearAirStep(size = 26f, line = 30f)

    /** The title of a sheet or a pushed screen. */
    val titleL = ClearAirStep(size = 20f, line = 25f)

    /** A row's title, and the label of a button. */
    val rowTitle = ClearAirStep(size = 15f, line = 20f)

    /** Running text. */
    val body = ClearAirStep(size = 15f, line = 22f)

    /** A row's value. */
    val rowValue = ClearAirStep(size = 14f, line = 20f)

    /** An address, set in groups of four. */
    val address = ClearAirStep(size = 13f, line = 19f)

    /** A status in words, and the testnet band. */
    val label = ClearAirStep(size = 13f, line = 18f)

    /** The clock time of a timeline step. */
    val time = ClearAirStep(size = 12f, line = 17f)

    /** An eyebrow, which is the one ALL CAPS style. */
    val eyebrow = ClearAirStep(size = 11f, line = 14f)

    /** The size of a currency sign, as a share of the figures beside it. */
    const val CURRENCY_SIGN_SHARE = 0.5f
}

private fun clearAirStyle(family: FontFamily, weight: FontWeight, step: ClearAirStep) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = step.size.sp,
    lineHeight = step.line.sp,
)

/** The Clear Air type scale: one style for each role of the proposal. The sizes come from [ClearAirScale]. */
object ClearAirType {
    /** The hero numeral of an overview: JetBrains Mono Light. */
    val hero = clearAirStyle(NumberFamily, FontWeight.Light, ClearAirScale.hero)

    /** A page total, left-aligned: JetBrains Mono Light. */
    val total = clearAirStyle(NumberFamily, FontWeight.Light, ClearAirScale.total)

    /** An amount in a flow: JetBrains Mono Light. */
    val amountLarge = clearAirStyle(NumberFamily, FontWeight.Light, ClearAirScale.amountLarge)

    /** The question of a screen: Archivo 114 Bold. */
    val titleXL = clearAirStyle(HeadingFamily, FontWeight.Bold, ClearAirScale.titleXL)

    /** The title of a sheet or a pushed screen: Archivo 114 Bold. */
    val titleL = clearAirStyle(HeadingFamily, FontWeight.Bold, ClearAirScale.titleL)

    /** A row's title, and the label of a button: Archivo 114 SemiBold. */
    val rowTitle = clearAirStyle(HeadingFamily, FontWeight.SemiBold, ClearAirScale.rowTitle)

    /** Running text: Archivo 92 Regular. */
    val body = clearAirStyle(BodyFamily, FontWeight.Normal, ClearAirScale.body)

    /** A row's value: JetBrains Mono Medium. */
    val rowValue = clearAirStyle(NumberFamily, FontWeight.Medium, ClearAirScale.rowValue)

    /** An address, set in groups of four: JetBrains Mono Regular. */
    val address = clearAirStyle(NumberFamily, FontWeight.Normal, ClearAirScale.address)

    /** A status in words, and the testnet band: Archivo 92 SemiBold. */
    val label = clearAirStyle(BodyFamily, FontWeight.SemiBold, ClearAirScale.label)

    /** The clock time of a timeline step: JetBrains Mono Medium. */
    val time = clearAirStyle(NumberFamily, FontWeight.Medium, ClearAirScale.time)

    /** An eyebrow, which is the one ALL CAPS style: Archivo 92 SemiBold. */
    val eyebrow = clearAirStyle(BodyFamily, FontWeight.SemiBold, ClearAirScale.eyebrow)

    /** The currency sign of an amount, raised like a superscript, so a mono sign never takes a whole column. */
    val currencySign = SpanStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = ClearAirScale.CURRENCY_SIGN_SHARE.em,
        baselineShift = BaselineShift.Superscript,
    )
}

/**
 * The Material typography of Clear Air. The roles the proposal does not list take the nearest listed style, so no text falls back to the system
 * face.
 */
val ClearAirTypography = Typography(
    displayLarge = ClearAirType.hero,
    displayMedium = ClearAirType.amountLarge,
    displaySmall = ClearAirType.total,
    headlineLarge = ClearAirType.titleXL,
    headlineMedium = ClearAirType.titleL,
    headlineSmall = ClearAirType.titleL,
    titleLarge = ClearAirType.titleL,
    titleMedium = ClearAirType.rowTitle,
    titleSmall = ClearAirType.rowTitle,
    bodyLarge = ClearAirType.body,
    bodyMedium = ClearAirType.body,
    bodySmall = ClearAirType.body,
    labelLarge = ClearAirType.rowTitle,
    labelMedium = ClearAirType.label,
    labelSmall = ClearAirType.eyebrow,
)
