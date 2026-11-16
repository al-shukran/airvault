package app.lumio.data.tx

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/** The amount sort key and the search words, which the store writes and the queries read. */
class TxIndexTest {
    @Test
    fun amountKeysSortInNumericOrder() {
        val nine = TxAmounts.key(BigInteger.valueOf(9), decimals = 18)
        val oneEth = TxAmounts.key(BigInteger.TEN.pow(18), decimals = 18)
        val tenEth = TxAmounts.key(BigInteger.TEN.pow(19), decimals = 18)

        assertEquals(TxAmounts.KEY_DIGITS, oneEth.length)
        assertTrue(nine < oneEth)
        assertTrue(oneEth < tenEth)
    }

    @Test
    fun coinsWithDifferentDecimalsShareOneScale() {
        // One whole USDC (6 decimals) and one whole ETH (18 decimals) are the same key, and a bound given in whole units compares with both.
        assertEquals(TxAmounts.key(BigInteger.TEN.pow(18), 18), TxAmounts.key(BigInteger.TEN.pow(6), 6))
        assertEquals(TxAmounts.keyOf(BigDecimal("1.5")), TxAmounts.key(BigInteger.valueOf(150_000_000), decimals = 8))
    }

    @Test
    fun aBoundAboveEveryAmountIsClampedAndNeverWraps() {
        val huge = TxAmounts.keyOf(BigDecimal("1e200"))

        assertEquals(TxAmounts.KEY_DIGITS, huge.length)
        assertTrue(huge > TxAmounts.key(BigInteger.TEN.pow(77), decimals = 0))
    }

    @Test
    fun aNegativeBoundIsRefused() {
        assertFailsWith<IllegalArgumentException> { TxAmounts.keyOf(BigDecimal("-1")) }
    }

    @Test
    fun aWordIsALowerCaseRunOfLettersAndDigits() {
        assertEquals(setOf("rent", "for", "march"), TxTerms.of("Rent, for MARCH!"))
        assertEquals(setOf("usd", "coin"), TxTerms.of("USD Coin"))
        assertEquals(setOf("0x" + "ab".repeat(32)), TxTerms.of("0x" + "AB".repeat(32)))
        assertEquals(emptySet(), TxTerms.of(null))
    }
}
