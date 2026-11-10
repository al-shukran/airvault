package app.lensly.core.registry

import java.math.BigInteger

/** Exact decimal formatting of integer token or native amounts. No floating-point arithmetic. */
object Amounts {
    /**
     * Formats [wei] as a decimal with [decimals] fractional digits, dropping trailing fractional zeros.
     * Examples: (0, 18) -> "0", (1, 18) -> "0.000000000000000001", (1_500_000, 6) -> "1.5".
     */
    fun format(wei: BigInteger, decimals: Int): String {
        require(wei.signum() >= 0) { "amount must not be negative" }
        require(decimals >= 0) { "decimals must not be negative" }
        val (whole, fraction) = wei.divideAndRemainder(BigInteger.TEN.pow(decimals))
        if (fraction.signum() == 0) return whole.toString()
        val digits = fraction.toString().padStart(decimals, '0').trimEnd('0')
        return "$whole.$digits"
    }
}
