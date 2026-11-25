package app.lumio.tokens

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner's balance as the Add Token page words it. */
class TokenHoldingTest {

    private val tiny = Token(1L, "0x6982508145454ce325ddbe47a25d4ec3d2311933", "TINY", "Tiny Test Token", 18)
    private val six = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)

    @Test
    fun aWholeAmountShowsGroupedDigitsAndTheSymbol() {
        assertEquals("1,200 TINY", holdingText(BigInteger("1200") * BigInteger.TEN.pow(18), tiny))
    }

    @Test
    fun aFractionKeepsItsDigitsAndDropsTrailingZeros() {
        assertEquals("1.5 USDC", holdingText(BigInteger("1500000"), six))
    }

    @Test
    fun aZeroBalanceShowsZero() {
        assertEquals("0 TINY", holdingText(BigInteger.ZERO, tiny))
    }
}
