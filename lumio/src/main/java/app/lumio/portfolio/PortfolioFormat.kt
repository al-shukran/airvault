package app.lumio.portfolio

import app.lumio.home.HomeFormat
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

    /** A dollar amount, such as "$149,769.10". */
    fun usd(value: Double): String = HomeFormat.usd(value)

    /** A dollar amount with its sign, such as "−$1,728.13" or "$1,728.13". */
    fun signedUsd(value: Double): String = (if (value < 0) "−" else "") + HomeFormat.usd(abs(value))

    /** A change in percent with its arrow, such as "▼ 1.15%" or "▲ 1.82%". A change of nothing has no arrow. */
    fun change(percent: Double): String = when {
        percent < 0 -> "▼ ${String.format(Locale.ROOT, "%.2f%%", abs(percent))}"
        percent > 0 -> "▲ ${String.format(Locale.ROOT, "%.2f%%", percent)}"
        else -> "0.00%"
    }
}
