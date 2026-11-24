package app.lumio.data.config

import app.lumio.data.provider.FiatQuote
import java.util.Locale

/**
 * A currency the owner can show prices and totals in. Every amount is held in US dollars, and it is converted only where it is shown, so [symbol] and
 * [digits] are all a currency adds. [title] names it on the Currency sheet.
 */
enum class FiatCurrency(val code: String, val title: String, val symbol: String, val digits: Int) {
    USD("USD", "US Dollar", "$", 2),
    EUR("EUR", "Euro", "€", 2),
    GBP("GBP", "British Pound", "£", 2),
    JPY("JPY", "Japanese Yen", "¥", 0),
    CHF("CHF", "Swiss Franc", "CHF", 2),
    ;

    /** The sign before an amount: the symbol, with a space when it is a word such as "CHF". */
    val prefix: String get() = if (symbol.length == 1) symbol else "$symbol "

    /** The amount [value] in this currency, with its symbol and grouping commas, such as "€1,234.56" or "¥1,235". The text is the same in every locale. */
    fun format(value: Double): String = prefix + String.format(Locale.ROOT, "%,.${digits}f", value)
}

/** How many units of [currency] one US dollar buys. It is kept with its currency, so it is never applied to another one. */
data class FiatRate(val currency: FiatCurrency, val perUsd: Double)

/** A rate read longer ago than this is not used, so amounts show in US dollars until a newer rate is read. No marker says that a rate was old. */
private const val FIAT_RATE_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

/**
 * The rate of [currency] in [quote] as of [nowMs]. Null when the quote has no rate for the currency, or when it was read more than [FIAT_RATE_MAX_AGE_MS]
 * before [nowMs], so the amounts fall back to US dollars rather than use a stale rate.
 */
fun fiatRateOf(quote: FiatQuote, currency: FiatCurrency, nowMs: Long): FiatRate? {
    val perUsd = quote.perUsd[currency.code] ?: return null
    return if (nowMs - quote.asOfMs > FIAT_RATE_MAX_AGE_MS) null else FiatRate(currency, perUsd)
}

/**
 * The currency that amounts show in, and how many of its units one US dollar buys. It is built with [of], which falls back to US dollars when no rate is
 * known, so an amount is never shown in a currency with a wrong number.
 */
class Fiat private constructor(val currency: FiatCurrency, private val perUsd: Double) {
    /** [usd] dollars as a number of this currency. */
    fun convert(usd: Double): Double = usd * perUsd

    /** [usd] dollars in this currency, such as "€1,234.56". */
    fun amount(usd: Double): String = currency.format(convert(usd))

    companion object {
        /** US dollars, which every amount is held in, so it needs no rate. */
        val USD: Fiat = Fiat(FiatCurrency.USD, 1.0)

        /**
         * The [currency] at [rate]. US dollars when the rate is not known, is not positive, or prices another currency, so the wrong currency is never shown
         * and a rate from before a currency switch is never applied.
         */
        fun of(currency: FiatCurrency, rate: FiatRate?): Fiat =
            if (currency == FiatCurrency.USD || rate == null || rate.currency != currency || rate.perUsd <= 0.0) USD else Fiat(currency, rate.perUsd)
    }
}
