package app.lumio.portfolio

import app.lumio.data.config.Fiat
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    /**
     * The caption of a last-known price: "as of 14:05" when [asOfMs] is on the same day as [nowMs] in [zone], else with the day, such as "as of 25 Nov".
     */
    fun asOf(asOfMs: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(asOfMs).atZone(zone)
        val today = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return "as of " + (if (today) CLOCK else DAY).format(at)
    }

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** A change in percent with its arrow, such as "▼ 1.15%" or "▲ 1.82%". A change of nothing has no arrow. */
    fun change(percent: Double): String = when {
        percent < 0 -> "▼ ${String.format(Locale.ROOT, "%.2f%%", abs(percent))}"
        percent > 0 -> "▲ ${String.format(Locale.ROOT, "%.2f%%", percent)}"
        else -> "0.00%"
    }
}
