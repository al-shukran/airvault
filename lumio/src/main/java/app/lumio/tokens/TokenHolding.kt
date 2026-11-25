package app.lumio.tokens

import java.math.BigDecimal
import java.math.BigInteger
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The owner's [amount] of [token], in its smallest units, as the Add Token page words it: whole units with grouped digits, then the symbol. */
fun holdingText(amount: BigInteger, token: Token): String {
    val units = BigDecimal(amount).movePointLeft(token.decimals).stripTrailingZeros()
    return "${DecimalFormat("#,##0.##########", DecimalFormatSymbols(Locale.ROOT)).format(units)} ${token.symbol}"
}
