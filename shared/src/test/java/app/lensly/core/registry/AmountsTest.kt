package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class AmountsTest {
    private fun fmt(value: Long, decimals: Int): String =
        Amounts.format(BigInteger.valueOf(value), decimals)

    @Test
    fun zeroIsZeroAtAnyScale() {
        assertEquals("0", fmt(0, 0))
        assertEquals("0", fmt(0, 6))
        assertEquals("0", fmt(0, 18))
    }

    @Test
    fun oneWeiAtEighteenDecimals() {
        assertEquals("0.000000000000000001", fmt(1, 18))
    }

    @Test
    fun oneWholeEtherAtEighteenDecimals() {
        assertEquals("1", Amounts.format(BigInteger.TEN.pow(18), 18))
    }

    @Test
    fun trailingZerosAreTrimmedAtEighteenDecimals() {
        assertEquals("1.5", Amounts.format(BigInteger("1500000000000000000"), 18))
    }

    @Test
    fun sixDecimalsKeepLeadingFractionZeros() {
        assertEquals("0.000001", fmt(1, 6))
        assertEquals("0.00001", fmt(10, 6))
        assertEquals("0.05", fmt(50_000, 6))
    }

    @Test
    fun sixDecimalsTrimTrailingZeros() {
        assertEquals("1.5", fmt(1_500_000, 6))
        assertEquals("1", fmt(1_000_000, 6))
        assertEquals("1234.567891", fmt(1_234_567_891, 6))
    }

    @Test
    fun zeroDecimalsIsPlainInteger() {
        assertEquals("123456789", fmt(123_456_789, 0))
    }

    @Test
    fun maxUint256AtEighteenDecimalsIsExact() {
        val max = ContractCall.MAX_UINT256
        assertEquals(
            "115792089237316195423570985008687907853269984665640564039457.584007913129639935",
            Amounts.format(max, 18),
        )
    }

    @Test
    fun negativeAmountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Amounts.format(BigInteger.valueOf(-1), 18)
        }
    }

    @Test
    fun negativeDecimalsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            Amounts.format(BigInteger.ONE, -1)
        }
    }
}
