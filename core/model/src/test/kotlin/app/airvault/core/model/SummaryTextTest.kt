package app.airvault.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryTextTest {
    @Test
    fun aSendIsWrittenAsItsQuestionWithEveryAddressInFull() {
        assertEquals("Send 0.4 ALGO from AAA to BBB?", listOf(SummaryPart.Send("0.4", "ALGO", "AAA", "BBB")).text())
    }

    @Test
    fun aTrimmedAmountDropsOnlyTheTrailingZeros() {
        assertEquals("0.4", trimmedAmount(BigInteger.valueOf(400_000), 6))
        assertEquals("1", trimmedAmount(BigInteger.valueOf(1_000_000), 6))
        assertEquals("1000", trimmedAmount(BigInteger.valueOf(1_000_000_000), 6))
        assertEquals("0.000001", trimmedAmount(BigInteger.ONE, 6))
        assertEquals("0", trimmedAmount(BigInteger.ZERO, 6))
    }

    @Test
    fun aTrimmedAmountKeepsEveryOtherDigitOfAnExactAmount() {
        assertEquals("123456789012.34567890123456789", trimmedAmount(BigInteger("123456789012345678901234567890"), 18))
    }
}
