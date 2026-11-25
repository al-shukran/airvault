package app.lumio.tokens

import java.math.BigDecimal
import java.math.BigInteger
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private const val FRACTION_DIGITS = 10

/** The owner's [amount] of [token], in its smallest units, as the Add Token page words it: whole units with grouped digits, then the symbol. Dust below the shown precision reads "< 0.0000000001", never "0". */
fun holdingText(amount: BigInteger, token: Token): String {
    val units = BigDecimal(amount).movePointLeft(token.decimals).stripTrailingZeros()
    val format = DecimalFormat("#,##0.${"#".repeat(FRACTION_DIGITS)}", DecimalFormatSymbols(Locale.ROOT))
    val text = format.format(units)
    val shown = if (units.signum() != 0 && text == "0") "< ${format.format(BigDecimal.ONE.movePointLeft(FRACTION_DIGITS))}" else text
    return "$shown ${token.symbol}"
}
