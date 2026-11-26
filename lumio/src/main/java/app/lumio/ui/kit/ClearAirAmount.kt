package app.lumio.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

private const val MINUS_SIGN = '−'

/** What a hidden amount shows in place of its digits. */
const val HIDDEN_AMOUNT = "••••"

/**
 * An amount, such as a balance, set in JetBrains Mono Light. Its currency sign is small and raised, and its decimal fraction is dimmed, so the whole
 * number reads first. With [dimUnit], the unit after the number is dimmed instead, as the hero's "T" is: a compact figure keeps its fraction in ink. A
 * hidden amount shows [HIDDEN_AMOUNT], which never shows how large the amount is.
 */
@Composable
fun ClearAirAmount(
    text: String,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    dimUnit: Boolean = false,
    style: TextStyle = ClearAirType.total,
    color: Color = LocalClearAirPalette.current.ink,
) {
    Text(
        text = if (hidden) AnnotatedString(HIDDEN_AMOUNT) else amountText(text, LocalClearAirPalette.current.ink2, dimUnit),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

private fun amountText(text: String, dim: Color, dimUnit: Boolean): AnnotatedString {
    val parts = splitAmount(text)
    val dimmed = SpanStyle(color = dim)
    return buildAnnotatedString {
        withStyle(ClearAirType.currencySign) { append(parts.prefix) }
        append(parts.whole)
        withStyle(if (dimUnit) SpanStyle() else dimmed) { append(parts.fraction) }
        withStyle(if (dimUnit) dimmed else SpanStyle()) { append(parts.suffix) }
    }
}

/**
 * A formatted amount in parts: the [prefix] before the number, such as a currency sign; the [whole] digits with any grouping commas; the [fraction],
 * which starts at the decimal point; and the [suffix] after the number, such as a unit.
 */
internal data class AmountParts(val prefix: String, val whole: String, val fraction: String, val suffix: String)

/**
 * Splits a formatted amount such as "$ 149,769.10", "0.25 MON" or "-6%". The number starts at its first digit or sign and runs over digits, grouping
 * commas and the decimal point. Text with no number is all prefix.
 */
internal fun splitAmount(text: String): AmountParts {
    val start = text.indexOfFirst { it.isDigit() || it == '-' || it == '+' || it == MINUS_SIGN }
    if (start < 0) return AmountParts(prefix = text, whole = "", fraction = "", suffix = "")
    var end = start + 1
    while (end < text.length && (text[end].isDigit() || text[end] == ',' || text[end] == '.')) end++
    val number = text.substring(start, end)
    val point = number.indexOf('.').let { if (it < 0) number.length else it }
    return AmountParts(
        prefix = text.substring(0, start),
        whole = number.substring(0, point),
        fraction = number.substring(point),
        suffix = text.substring(end),
    )
}
