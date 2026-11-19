package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Test

class StepCallTest {
    @Test
    fun aDeadlineInTheClockRangeIsShownAsADate() {
        assertEquals("2027-01-15 08:00 UTC", untilText(BigInteger.valueOf(1_800_000_000L)))
    }

    @Test
    fun aDeadlineBeyondTheClockRangeIsShownAsTheRawNumberNotAsATruncatedDate() {
        // 2^64 + 1,800,000,000: truncating it to a Long would show 2027-01-15 08:00 UTC, a date the word does not name.
        val huge = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(1_800_000_000L))

        assertEquals("$huge (not a date)", untilText(huge))
    }
}
