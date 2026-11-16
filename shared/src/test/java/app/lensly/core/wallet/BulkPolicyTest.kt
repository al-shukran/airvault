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
    fun aCountBelowOneIsRefused() {
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(0, cap = 100))
        assertEquals(CountCheck.TooFew, BulkPolicy.checkCount(-5, cap = 100))
    }

    @Test
    fun noCountTypedIsMissingNotRefused() {
        assertEquals(CountCheck.Missing, BulkPolicy.checkCount(null, cap = 100))
    }

    @Test
    fun anEmptyCountFieldIsMissingNotRefused() {
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped("", cap = 100))
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped("", cap = 20))
    }

    @Test
    fun aTypedCountIsBoundedByTheCap() {
        assertEquals(CountCheck.Valid(1), BulkPolicy.checkTyped("1", cap = 100))
        assertEquals(CountCheck.Valid(100), BulkPolicy.checkTyped("100", cap = 100))
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkTyped("101", cap = 100))
        assertEquals(CountCheck.Valid(20), BulkPolicy.checkTyped("20", cap = 20))
        assertEquals(CountCheck.TooMany(20), BulkPolicy.checkTyped("21", cap = 20))
    }

    @Test
    fun aTypedZeroIsTooFew() {
        assertEquals(CountCheck.TooFew, BulkPolicy.checkTyped("0", cap = 100))
        assertEquals(CountCheck.TooFew, BulkPolicy.checkTyped("000", cap = 100))
    }

    @Test
    fun aLeadingZeroDoesNotChangeTheCount() {
        assertEquals(CountCheck.Valid(7), BulkPolicy.checkTyped("007", cap = 100))
    }

    @Test
    fun theSameTypedCountIsRecheckedWhenTheCapChanges() {
        // "50" is refused under the 20 cap, then accepted once the Bulk Cap is raised in Settings.
        assertEquals(CountCheck.TooMany(20), BulkPolicy.checkTyped("50", cap = 20))
        assertEquals(CountCheck.Valid(50), BulkPolicy.checkTyped("50", cap = 50))
        assertEquals(CountCheck.Valid(50), BulkPolicy.checkTyped("50", cap = 100))
    }

    @Test
    fun textWithAnythingButDigitsIsMissing() {
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped("1a", cap = 100))
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped("-5", cap = 100))
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped(" 5", cap = 100))
        assertEquals(CountCheck.Missing, BulkPolicy.checkTyped("5.0", cap = 100))
    }

    @Test
    fun aCountTooLargeForAnIntIsAboveEveryCap() {
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkTyped("99999999999999", cap = 100))
        assertEquals(CountCheck.TooMany(100), BulkPolicy.checkTyped("999999999", cap = 100))
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
}
