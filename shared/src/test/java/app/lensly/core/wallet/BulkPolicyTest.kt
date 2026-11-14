package app.lensly.core.wallet

import app.lensly.core.wallet.BulkPolicy.CountCheck
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class BulkPolicyTest {

    @Test
    fun theBulkCapDefaultsToFiftyAndOffersTwentyFiftyAndHundred() {
        assertEquals(50, BulkCap.DEFAULT.accounts)
        assertEquals(listOf(20, 50, 100), BulkCap.entries.map { it.accounts })
    }

    @Test
    fun aCapIsFoundByItsAccountCount() {
        assertEquals(BulkCap.HUNDRED, BulkCap.fromAccounts(100))
        assertEquals(null, BulkCap.fromAccounts(1_000))
        assertEquals(null, BulkCap.fromAccounts(500))
    }

    @Test
    fun capsAreShownWithThousandsSeparators() {
        assertEquals("20", BulkCap.TWENTY.label)
        assertEquals("50", BulkCap.FIFTY.label)
        assertEquals("100", BulkCap.HUNDRED.label)
        assertEquals("10,000", formatAccounts(10_000))
    }

    @Test
    fun thePresetsAreTheCapAndTenAndTwenty() {
        assertEquals(listOf(10, 20), BulkPolicy.presetsFor(20))
        assertEquals(listOf(10, 20, 50), BulkPolicy.presetsFor(50))
        assertEquals(listOf(10, 20, 100), BulkPolicy.presetsFor(100))
    }

    @Test
    fun aPresetIsNeverAboveTheCapAndNeverRepeated() {
        // The cap itself is one of the candidates, so it is the only preset under a cap below 10.
        assertEquals(listOf(10, 15), BulkPolicy.presetsFor(15))
        assertEquals(listOf(5), BulkPolicy.presetsFor(5))
    }

    @Test
    fun everyPresetIsAValidCountUnderItsCap() {
        for (cap in BulkCap.entries) {
            for (preset in BulkPolicy.presetsFor(cap.accounts)) {
                assertEquals(CountCheck.Valid(preset), BulkPolicy.checkCount(preset, cap.accounts), "preset $preset")
            }
        }
    }

    @Test
    fun aCountFromOneUpToTheCapIsValid() {
        assertEquals(CountCheck.Valid(1), BulkPolicy.checkCount(1, cap = 100))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100, cap = 100))
        assertEquals(CountCheck.Valid(20), BulkPolicy.checkCount(20, cap = 20))
    }

    @Test
    fun aCountAboveTheCapIsRefused() {
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(101, cap = 100))
        assertEquals(CountCheck.TooMany(20), BulkPolicy.checkCount(21, cap = 20))
    }

    @Test
    fun theConfiguredCapDecidesWhatIsRefused() {
        // The same Count is refused under the 20 cap and accepted under the 100 cap.
        assertEquals(CountCheck.TooMany(20), BulkPolicy.checkCount(50, cap = 20))
        assertEquals(CountCheck.Valid(50), BulkPolicy.checkCount(50, cap = 100))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100, cap = 100))
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(101, cap = 100))
    }

    @Test
    fun aHundredCapRefusesOneHundredAndOneAndAllowsOneHundred() {
        val cap = BulkCap.HUNDRED.accounts
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(101, cap))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100, cap))
    }

    @Test
    fun aTypedCountCanReachTheLargestCap() {
        assertEquals("100", BulkPolicy.appendDigit("10", '0'))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount("100".toInt(), BulkCap.HUNDRED.accounts))
        assertEquals(3, BulkPolicy.MAX_DIGITS)
    }

    @Test
    fun aTypedCountStopsAtTheLargestCapsDigits() {
        assertEquals("100", BulkPolicy.appendDigit("100", '0'))
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount("999".toInt(), BulkCap.HUNDRED.accounts))
    }

    @Test
    fun aCountBelowOneIsRefused() {
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(0, cap = 100))
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(-5, cap = 100))
    }

    @Test
    fun noTypedCountIsMissingNotRefused() {
        assertEquals(CountCheck.Missing, BulkPolicy.checkCount(null, cap = 100))
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
