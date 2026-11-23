package app.lumio.portfolio

import app.lumio.data.config.Fiat
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * The text of the balances and the activity: coins with grouping commas and up to eight decimals, dollars with a true minus sign, and a change with its
 * arrow. The text is the same in every locale, so the goldens and the tests read it the same everywhere.
 */
object PortfolioFormat {
    /** A coin amount in whole coins, such as "22.23" or "20,100". */
    fun coins(amount: BigDecimal): String = DecimalFormat("#,##0.########", DecimalFormatSymbols(Locale.ROOT)).format(amount)

    /** An amount in the owner's currency with its sign, such as "−$1,728.13" or "$1,728.13". */
    fun signedAmount(usd: Double, fiat: Fiat): String = (if (usd < 0) "−" else "") + fiat.amount(abs(usd))

    /** A change in percent with its arrow, such as "▼ 1.15%" or "▲ 1.82%". A change of nothing has no arrow. */
    fun change(percent: Double): String = when {
        percent < 0 -> "▼ ${String.format(Locale.ROOT, "%.2f%%", abs(percent))}"
        percent > 0 -> "▲ ${String.format(Locale.ROOT, "%.2f%%", percent)}"
        else -> "0.00%"
    }
}
