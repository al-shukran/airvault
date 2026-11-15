package app.lumio.data.tx

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale

/**
 * The sort key of an amount. It is the amount in whole units times 10^18, written as 96 digits with leading zeros. Text order is then numeric order,
 * and two coins with different decimals sort on one scale. A key of 96 digits holds every 256-bit amount at 18 places, and anything larger is clamped to
 * the widest key, so a filter bound can never wrap around.
 */
internal object TxAmounts {
    const val KEY_DIGITS = 96

    private val SCALE: BigInteger = BigInteger.TEN.pow(18)
    private val WIDEST: BigInteger = BigInteger.TEN.pow(KEY_DIGITS).subtract(BigInteger.ONE)

    /** The key of [baseUnits] of a coin with [decimals] decimals. Digits beyond 18 places are cut, which only ever affects the sort order of a tie. */
    fun key(baseUnits: BigInteger, decimals: Int): String =
        baseUnits.multiply(SCALE).divide(BigInteger.TEN.pow(decimals)).min(WIDEST).toString().padStart(KEY_DIGITS, '0')

    /** The key of a whole-unit amount, such as 1.5. It is the same scale as [key], so a filter bound compares with the stored keys. */
    fun keyOf(wholeUnits: BigDecimal): String {
        require(wholeUnits.signum() >= 0) { "An amount is not negative." }
        val scaled = wholeUnits.movePointRight(18).setScale(0, RoundingMode.DOWN).toBigInteger()
        return scaled.min(WIDEST).toString().padStart(KEY_DIGITS, '0')
    }
}

/**
 * The search words of a text: each run of letters and digits, in lower case, at most [MAX_PER_TEXT] of them. A transaction hash or an address is a single
 * run, so it is one word, and a search for the full value finds it. A partial hash is not a word, so it does not match.
 */
internal object TxTerms {
    private const val MAX_PER_TEXT = 256
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun of(text: String?): Set<String> = text.orEmpty().lowercase(Locale.ROOT).split(SPLIT).asSequence()
        .filter { it.isNotEmpty() }
        .take(MAX_PER_TEXT)
        .toSet()
}
