package app.lensly.core.wallet

import app.lensly.core.wallet.BulkPolicy.CountCheck
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BulkPolicyTest {

    @Test
    fun theBulkCapDefaultsToOneHundredAndOffersOneThousand() {
        assertEquals(100, BulkCap.DEFAULT.accounts)
        assertEquals(listOf(100, 1_000), BulkCap.entries.map { it.accounts })
    }

    @Test
    fun aCapIsFoundByItsAccountCount() {
        assertEquals(BulkCap.THOUSAND, BulkCap.fromAccounts(1_000))
        assertEquals(null, BulkCap.fromAccounts(500))
    }

    @Test
    fun capsAreShownWithThousandsSeparators() {
        assertEquals("100", BulkCap.HUNDRED.label)
        assertEquals("1,000", BulkCap.THOUSAND.label)
        assertEquals("10,000", formatAccounts(10_000))
    }

    @Test
    fun thePresetsAreTenTwentyAndFifty() {
        assertEquals(listOf(10, 20, 50), BulkPolicy.PRESETS)
    }

    @Test
    fun everyPresetIsAValidCountUnderEveryBulkCap() {
        for (cap in BulkCap.entries) {
            for (preset in BulkPolicy.PRESETS) {
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
        // The same Count is refused under the 100 cap and accepted under the 1,000 cap.
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(150, cap = 100))
        assertEquals(CountCheck.Valid(150), BulkPolicy.checkCount(150, cap = 1_000))
        assertEquals(CountCheck.Valid(1_000), BulkPolicy.checkCount(1_000, cap = 1_000))
        assertEquals(CountCheck.TooMany(1_000), BulkPolicy.checkCount(1_001, cap = 1_000))
    }

    @Test
    fun aHundredCapRefusesOneHundredAndOneAndAllowsOneHundred() {
        val cap = BulkCap.HUNDRED.accounts
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkCount(101, cap))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkCount(100, cap))
    }

    @Test
    fun aThousandCapAllowsOneThousandAndRefusesOneThousandAndOne() {
        val cap = BulkCap.THOUSAND.accounts
        assertEquals(CountCheck.Valid(1_000), BulkPolicy.checkCount(1_000, cap))
        assertEquals(CountCheck.TooMany(1_000), BulkPolicy.checkCount(1_001, cap))
    }

    @Test
    fun aTypedCountCanReachTheLargestCap() {
        assertEquals("1000", BulkPolicy.appendDigit("100", '0'))
        assertEquals(CountCheck.Valid(1_000), BulkPolicy.checkCount("1000".toInt(), BulkCap.THOUSAND.accounts))
        assertTrue(BulkPolicy.MAX_DIGITS >= BulkCap.THOUSAND.accounts.toString().length)
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
