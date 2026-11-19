package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.checkLabels
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import fr.acinq.bitcoin.KeyPath
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The arithmetic and the words of the Bitcoin review: what is sent, what comes back, what leaves the wallet, and how amounts read. */
class BitcoinReviewTest {

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

    private val testnet = NetworkLabel(name = "Bitcoin Testnet4", testnet = true, detail = null)

    /** A key of the account, so an input is owned and raises no external-input warning. */
    private val owned = KeyPath(listOf(0L))

    private fun reviewOf(review: BtcReview, nameOf: (String) -> String? = { null }): Review = review.toReview(
        chain = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae"),
        network = testnet,
        scriptType = "Native Segwit P2WPKH",
        nameOf = nameOf,
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
        assertEquals("0xFFFFFFFD, replace-by-fee on", sequenceText(rbf))
        assertEquals("0xFFFFFFFF, replace-by-fee off", sequenceText(final))
    }

    @Test
    fun aCleanRequestHasNoWarningAndEveryDecodedFieldIsShown() {
        val review = reviewOf(request(feeSats = 10_000L).copy(inputs = listOf(BtcReviewInput(0, 40_000L, true, owned, 0xFFFFFFFDL))))
        assertEquals(emptyList(), review.warnings)
        assertEquals(emptySet(), review.unaccounted())
        assertEquals("Send 0.0003 BTC to tb1qreceiver", review.summary.text())
        assertEquals(listOf("Network Fee", "Fee Rate", "Total", "Change Back"), review.facts.map { it.label })
    }

    @Test
    fun anUnverifiedAmountIsADangerWithTheCodeTheOwnerTicks() {
        val unverified = request(feeSats = null).copy(
            inputs = listOf(BtcReviewInput(0, 40_000L, amountVerified = false, ownerPath = owned, sequence = 0xFFFFFFFDL)),
            requiresConfirmation = true,
        )
        val warning = reviewOf(unverified).warnings.single()
        assertEquals(Danger.DANGER, warning.level)
        assertEquals(UNVERIFIED_AMOUNTS_CODE, warning.code)
        assertEquals("1 input amount(s) come from a witness UTXO only. They are not verified, so the fee is unknown until they are confirmed.", warning.message)
    }

    @Test
    fun anUnverifiedAmountUnderTheRelaxedPolicyIsACautionWithNoTickToOwe() {
        val relaxed = request(feeSats = null).copy(
            inputs = listOf(BtcReviewInput(0, 40_000L, amountVerified = false, ownerPath = owned, sequence = 0xFFFFFFFDL)),
            requiresConfirmation = false,
        )
        val review = reviewOf(relaxed)
        val warning = review.warnings.single()
        assertEquals(Danger.CAUTION, warning.level)
        assertEquals(emptyList(), review.checkLabels())
        assertNotNull(Approval.of(review, emptySet()))
    }

    @Test
    fun aSingleCounterpartyIsNamedByItsAddressBookNameWhenThereIsOne() {
        assertEquals("Send 0.0003 BTC to Alice", reviewOf(request(feeSats = 10_000L)) { _ -> "Alice" }.summary.text())
        assertEquals("Send 0.0003 BTC to tb1qreceiver", reviewOf(request(feeSats = 10_000L)).summary.text())
    }

    @Test
    fun aRequestThatPaysNothingOutsideTheWalletSaysSo() {
        val allChange = request(feeSats = 10_000L).copy(
            outputs = listOf(BtcReviewOutput(0, "tb1qchange", 60_000L, isOwnChange = true, changeIndex = 0)),
        )
        assertEquals("Pay nothing outside this wallet", reviewOf(allChange).summary.text())
    }

    @Test
    fun aLockTimeThatFinalInputsIgnoreIsNotAWarning() {
        val final = request(feeSats = 10_000L).copy(
            inputs = listOf(BtcReviewInput(0, 40_000L, true, owned, 0xFFFFFFFFL)),
            lockTime = 850_000L,
        )
        val review = reviewOf(final)
        assertTrue(review.warnings.isEmpty())
        assertFalse(review.sections.single { it.heading == "Transaction" }.rows.single { it.label == "Locktime" }.notes.isEmpty())
    }
}
