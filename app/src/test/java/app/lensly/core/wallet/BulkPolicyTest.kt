package app.lensly.core.wallet

import app.lensly.core.wallet.BulkPolicy.CountCheck
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class BulkPolicyTest {

    @Test
    fun theBulkCapDefaultsToOneHundred() {
        assertEquals(100, BulkPolicy.DEFAULT_CAP)
    }

    @Test
    fun thePresetsAreTenTwentyAndFifty() {
        assertEquals(listOf(10, 20, 50), BulkPolicy.PRESETS)
    }

    @Test
    fun everyPresetIsAValidCount() {
        for (preset in BulkPolicy.PRESETS) {
            assertEquals(CountCheck.Valid(preset), BulkPolicy.checkCount(preset), "preset $preset")
        }
    }

    @Test
    fun aCountFromOneUpToTheCapIsValid() {
        assertEquals(CountCheck.Valid(1), BulkPolicy.checkCount(1))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100, cap = 100))
    }

    @Test
    fun aCountAboveTheCapIsRefused() {
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(101))
        assertEquals(CountCheck.TooMany(20), BulkPolicy.checkCount(21, cap = 20))
    }

    @Test
    fun aCountBelowOneIsRefused() {
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(0))
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(-5))
    }

    @Test
    fun noTypedCountIsMissingNotRefused() {
        assertEquals(CountCheck.Missing, BulkPolicy.checkCount(null))
    }

    @Test
    fun theNewAccountsFollowTheExistingOnes() {
        assertEquals(3..12, BulkPolicy.indexRange(3, 10))
    }

    @Test
    fun theFirstRunOfAWalletStartsAtIndexZero() {
        assertEquals(0..9, BulkPolicy.indexRange(0, 10))
        assertEquals(0..0, BulkPolicy.indexRange(0, 1))
    }

    @Test
    fun aRunMayEndAtTheLastNonHardenedIndex() {
        // The account count stays an Int: the last account is Int.MAX_VALUE - 1.
        assertEquals((Int.MAX_VALUE - 1)..(Int.MAX_VALUE - 1), BulkPolicy.indexRange(Int.MAX_VALUE - 1, 1))
    }

    @Test
    fun aRunPastTheIntRangeIsRefused() {
        assertFailsWith<IllegalArgumentException> { BulkPolicy.indexRange(Int.MAX_VALUE - 1, 2) }
    }

    @Test
    fun aRunOfNoAccountsIsRefused() {
        assertFailsWith<IllegalArgumentException> { BulkPolicy.indexRange(3, 0) }
    }

    @Test
    fun aNegativeStartIsRefused() {
        assertFailsWith<IllegalArgumentException> { BulkPolicy.indexRange(-1, 1) }
    }

    @Test
    fun typingDigitsBuildsTheCount() {
        assertEquals("1", BulkPolicy.appendDigit("", '1'))
        assertEquals("12", BulkPolicy.appendDigit("1", '2'))
        assertEquals("120", BulkPolicy.appendDigit("12", '0'))
    }

    @Test
    fun aLeadingZeroIsReplacedNotKept() {
        assertEquals("0", BulkPolicy.appendDigit("", '0'))
        assertEquals("7", BulkPolicy.appendDigit("0", '7'))
        assertEquals("0", BulkPolicy.appendDigit("0", '0'))
    }

    @Test
    fun aZeroCanFollowOtherDigits() {
        assertEquals("10", BulkPolicy.appendDigit("1", '0'))
    }

    @Test
    fun typingStopsAtTheDigitLimit() {
        assertEquals("1234", BulkPolicy.appendDigit("1234", '5'))
    }

    @Test
    fun aNonDigitIsRefused() {
        assertFailsWith<IllegalArgumentException> { BulkPolicy.appendDigit("1", 'a') }
    }
}
