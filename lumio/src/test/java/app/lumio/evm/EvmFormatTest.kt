package app.lumio.evm

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The text of a native amount and the decoding of a JSON-RPC quantity. */
class EvmFormatTest {

    @Test
    fun aWholeAmountHasNoFraction() {
        assertEquals("1 ETH", EvmFormat.amount(BigInteger("1000000000000000000"), 18, "ETH"))
    }

    @Test
    fun aFractionDropsItsTrailingZeros() {
        assertEquals("1.5 ETH", EvmFormat.amount(BigInteger("1500000000000000000"), 18, "ETH"))
    }

    @Test
    fun theSmallestUnitKeepsEveryDigit() {
        assertEquals("0.000000000000000001 ETH", EvmFormat.amount(BigInteger.ONE, 18, "ETH"))
    }

    @Test
    fun zeroIsZeroWithItsSymbol() {
        assertEquals("0 MON", EvmFormat.amount(BigInteger.ZERO, 18, "MON"))
    }

    @Test
    fun aChainWithNoDecimalsShowsTheWholeNumber() {
        assertEquals("12345 SYM", EvmFormat.amount(BigInteger.valueOf(12345), 0, "SYM"))
    }

    @Test
    fun aQuantityIsHexadecimalAndAcceptsUpperCase() {
        assertEquals(BigInteger.ZERO, parseQuantity("0x0"))
        assertEquals(BigInteger.valueOf(16), parseQuantity("0x10"))
        assertEquals(BigInteger.valueOf(255), parseQuantity("0xFF"))
    }

    @Test
    fun textThatIsNotAQuantityIsMalformed() {
        val failure = assertThrows(RpcFailure::class.java) { parseQuantity("10") }
        assertEquals(RpcFailure.Reason.MALFORMED, failure.reason)
    }
}
