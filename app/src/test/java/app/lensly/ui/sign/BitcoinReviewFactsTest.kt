package app.lensly.ui.sign

import app.lensly.core.btc.BtcReview
import app.lensly.core.btc.BtcReviewInput
import app.lensly.core.btc.BtcReviewOutput
import app.lensly.core.btc.BtcWarning
import app.lensly.core.btc.InputVerificationPolicy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The arithmetic behind the Bitcoin review's summary: what is sent, what comes back, what leaves the wallet, and how amounts read. */
class BitcoinReviewFactsTest {

    /** A request that sends 30000 sats to a receiver and returns 60000 sats to the account, with the given fee. */
    private fun request(feeSats: Long?) = BtcReview(
        inputPolicy = InputVerificationPolicy.STRICT,
        inputs = emptyList(),
        outputs = listOf(
            BtcReviewOutput(0, "tb1qreceiver", 30_000L, isOwnChange = false, changeIndex = null),
            BtcReviewOutput(1, "tb1qchange", 60_000L, isOwnChange = true, changeIndex = 0),
        ),
        feeSats = feeSats,
        requiresConfirmation = false,
        estimatedVsize = 141L,
        lockTime = 0L,
    )

    @Test
    fun theSentAmountIsTheOutputsThatAreNotChange() {
        assertEquals(30_000L, request(feeSats = 10_000L).sentToOthersSats())
    }

    @Test
    fun theChangeBackIsTheOutputsThatAreChange() {
        assertEquals(60_000L, request(feeSats = 10_000L).changeBackSats())
    }

    @Test
    fun theTotalIsWhatIsSentPlusTheFee() {
        assertEquals(40_000L, request(feeSats = 10_000L).totalSats())
    }

    @Test
    fun theTotalIsUnknownWhileTheFeeIs() {
        assertNull(request(feeSats = null).totalSats())
    }

    @Test
    fun anAmountDropsOnlyTheZerosAtItsEnd() {
        assertEquals("0.0003 BTC", btcShort(30_000L))
        assertEquals("1 BTC", btcShort(100_000_000L))
        assertEquals("10 BTC", btcShort(1_000_000_000L))
        assertEquals("0 BTC", btcShort(0L))
    }

    @Test
    fun aLockTimeBelowTheThresholdIsABlockHeight() {
        assertEquals("None", lockTimeText(0L))
        assertEquals("Block 850000", lockTimeText(850_000L))
        assertEquals("The transaction cannot confirm before block 850000.", warningText(BtcWarning.LockTime(850_000L)))
    }

    @Test
    fun aLockTimeFromTheThresholdUpIsAUtcDateAndTime() {
        assertEquals("15 Jan 2027 08:00 UTC", lockTimeText(1_800_000_000L))
        assertEquals(
            "The transaction cannot confirm before 15 Jan 2027 08:00 UTC.",
            warningText(BtcWarning.LockTime(1_800_000_000L)),
        )
    }

    @Test
    fun aSequenceShowsItsHexAndItsReplaceByFeeState() {
        val rbf = BtcReviewInput(0, 100_000L, amountVerified = true, ownerPath = null, sequence = 0xFFFFFFFDL)
        val final = rbf.copy(sequence = 0xFFFFFFFFL)
        assertEquals("Sequence 0xFFFFFFFD, replace-by-fee on", sequenceText(rbf))
        assertEquals("Sequence 0xFFFFFFFF, replace-by-fee off", sequenceText(final))
    }
}
