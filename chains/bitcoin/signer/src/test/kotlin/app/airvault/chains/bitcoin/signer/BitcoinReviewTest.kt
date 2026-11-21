package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.FieldPath
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.checkLabels
import app.airvault.core.model.text
import app.airvault.core.model.unaccounted
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.TxHash
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

    /**
     * The outputs that two inputs spend, so each input names its own output. The txids are not palindromes, so a change of byte order
     * changes the text below.
     */
    private val first = OutPoint(TxHash(ByteVector32(ByteArray(32) { it.toByte() })), 0L)
    private val second = OutPoint(TxHash(ByteVector32(ByteArray(32) { (it + 0x20).toByte() })), 1L)

    /** The text of [first] and [second]: the txid in Bitcoin's byte-reversed display order, then the output index. */
    private val firstText = "1f1e1d1c1b1a19181716151413121110" + "0f0e0d0c0b0a09080706050403020100" + ":0"
    private val secondText = "3f3e3d3c3b3a39383736353433323130" + "2f2e2d2c2b2a29282726252423222120" + ":1"

    /** The address of the account the requests spend from, which the summary names as the sender. */
    private val account = "tb1qaccount"

    private fun reviewOf(review: BtcReview): Review = review.toReview(
        chain = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae"),
        network = testnet,
        scriptType = "Native Segwit P2WPKH",
        from = account,
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
        val rbf = BtcReviewInput(0, first, 100_000L, amountVerified = true, ownerPath = null, sequence = 0xFFFFFFFDL)
        val final = rbf.copy(sequence = 0xFFFFFFFFL)
        assertEquals("0xFFFFFFFD, replace-by-fee on", sequenceText(rbf))
        assertEquals("0xFFFFFFFF, replace-by-fee off", sequenceText(final))
    }

    @Test
    fun aCleanRequestWarnsOnlyThatItsInputsWereNotChecked() {
        val review = reviewOf(request(feeSats = 10_000L).copy(inputs = listOf(BtcReviewInput(0, first, 40_000L, true, owned, 0xFFFFFFFDL))))
        assertEquals(
            listOf(Danger.CAUTION to "Inputs were not checked for inscriptions or runes."),
            review.warnings.map { it.level to it.message },
        )
        assertEquals(emptySet(), review.unaccounted())
        assertEquals("Send 0.0003 BTC from tb1qaccount to tb1qreceiver?", review.summary.text())
        assertEquals(listOf("Network Fee", "Fee Rate", "Total", "Change Back"), review.facts.map { it.label })
    }

    @Test
    fun anUnverifiedAmountIsADangerWithTheCodeTheOwnerTicks() {
        val unverified = request(feeSats = null).copy(
            inputs = listOf(BtcReviewInput(0, first, 40_000L, amountVerified = false, ownerPath = owned, sequence = 0xFFFFFFFDL)),
            requiresConfirmation = true,
        )
        val warning = reviewOf(unverified).warnings.single { it.code == UNVERIFIED_AMOUNTS_CODE }
        assertEquals(Danger.DANGER, warning.level)
        assertEquals("1 input amount(s) come from a witness UTXO only. They are not verified, so the fee is unknown until they are confirmed.", warning.message)
    }

    @Test
    fun anUnverifiedAmountUnderTheRelaxedPolicyIsACautionWithNoTickToOwe() {
        val relaxed = request(feeSats = null).copy(
            inputs = listOf(BtcReviewInput(0, first, 40_000L, amountVerified = false, ownerPath = owned, sequence = 0xFFFFFFFDL)),
            requiresConfirmation = false,
        )
        val review = reviewOf(relaxed)
        val warning = review.warnings.single { it.code == UNVERIFIED_AMOUNTS_CODE }
        assertEquals(Danger.CAUTION, warning.level)
        assertEquals(emptyList(), review.checkLabels())
        assertNotNull(Approval.of(review, emptySet()))
    }

    @Test
    fun aSingleOutsideOutputIsASendOfItsAmountFromTheAccountToItsAddress() {
        val send = reviewOf(request(feeSats = 10_000L)).summary.single()
        assertEquals(SummaryPart.Send(amount = "0.0003", asset = "BTC", from = account, to = "tb1qreceiver"), send)
    }

    @Test
    fun anOutsideOutputWithNoAddressIsNamedAsAScript() {
        val scriptOnly = request(feeSats = 10_000L).copy(
            outputs = listOf(
                BtcReviewOutput(0, null, 30_000L, isOwnChange = false, changeIndex = null),
                BtcReviewOutput(1, "tb1qchange", 60_000L, isOwnChange = true, changeIndex = 0),
            ),
        )
        assertEquals(listOf(SummaryPart.Text("Send 0.0003 BTC to a script with no address")), reviewOf(scriptOnly).summary)
    }

    /** The script row of the one output with no address in a request, given that output's script in hex. */
    private fun scriptRowOf(scriptHex: String): String? {
        val script = ByteArray(scriptHex.length / 2) { scriptHex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val review = reviewOf(
            request(feeSats = 10_000L).copy(
                outputs = listOf(
                    BtcReviewOutput(0, null, 0L, isOwnChange = false, changeIndex = null, script = script),
                    BtcReviewOutput(1, "tb1qchange", 60_000L, isOwnChange = true, changeIndex = 0),
                ),
            ),
        )
        return review.sections.single { it.heading == "Outputs" }.rows.single { it.label == "Script" }.value?.text()
    }

    @Test
    fun anOutputWithNoAddressShowsItsScript() {
        // OP_RETURN pushing "hello": the payload as hex and as text.
        assertEquals("68656c6c6f (hello)", scriptRowOf("6a0568656c6c6f"))
        // OP_RETURN pushing bytes that are not printable ASCII: hex only.
        assertEquals("00ff0102", scriptRowOf("6a0400ff0102"))
        // OP_RETURN that pushes nothing: its own bytes.
        assertEquals("6a", scriptRowOf("6a"))
        // A non-standard script, OP_1 OP_2 OP_ADD: all of its bytes as hex, with no text.
        assertEquals("515293", scriptRowOf("515293"))
        // OP_RETURN whose push is cut short: the script's own bytes, and the review still builds.
        assertEquals("6a4c", scriptRowOf("6a4c"))
        assertEquals("6a05aa", scriptRowOf("6a05aa"))
    }

    @Test
    fun anOpReturnWithOtherOpcodesOrMorePushesShowsAllItsBytes() {
        // OP_RETURN OP_13 <push "hello">: a Runes tag before the data, so the data form would hide the tag.
        assertEquals("6a5d0568656c6c6f", scriptRowOf("6a5d0568656c6c6f"))
        // OP_RETURN with two pushes, "hello" and "world": the data form would merge them into one text.
        assertEquals("6a0568656c6c6f05776f726c64", scriptRowOf("6a0568656c6c6f05776f726c64"))
    }

    @Test
    fun aPushOfOneByteAndANonPushOpcodeShowDifferentScripts() {
        // OP_RETURN pushing the one byte 0xff: its data.
        assertEquals("ff", scriptRowOf("6a01ff"))
        // OP_RETURN followed by 0xff, which is not a push: all of its bytes, so it does not read as the data above.
        assertEquals("6aff", scriptRowOf("6aff"))
    }

    @Test
    fun anOpReturnWithANonMinimalPushShowsAllItsBytes() {
        // OP_PUSHDATA1 for one byte, where a direct push of the byte is the canonical encoding.
        assertEquals("6a4c01ff", scriptRowOf("6a4c01ff"))
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
            inputs = listOf(BtcReviewInput(0, first, 40_000L, true, owned, 0xFFFFFFFFL)),
            lockTime = 850_000L,
        )
        val review = reviewOf(final)
        assertTrue(review.warnings.none { it.code == "bitcoin.lock-time" })
        assertFalse(review.sections.single { it.heading == "Transaction" }.rows.single { it.label == "Locktime" }.notes.isEmpty())
    }

    @Test
    fun aMarkedInputIsADangerOnItsAmountThatTheOwnerTicksBeforeSigning() {
        val marked = request(feeSats = 10_000L).copy(
            inputs = listOf(BtcReviewInput(0, first, 40_000L, true, owned, 0xFFFFFFFDL, riskMarked = true)),
        )
        val review = reviewOf(marked)
        val warning = review.warnings.single()
        assertEquals(Danger.DANGER, warning.level)
        assertEquals("bitcoin.risk-marked", warning.code)
        assertEquals(FieldPath("bitcoin.input.0.amount"), warning.field)
        assertEquals(listOf(warning.acknowledge), review.checkLabels())
        assertNull(Approval.of(review, emptySet()))
        assertNotNull(Approval.of(review, setOf(checkNotNull(warning.acknowledge))))
    }

    @Test
    fun aMixedRequestGivesADangerForEachMarkedInputAndOneCautionForTheRest() {
        val mixed = request(feeSats = 10_000L).copy(
            inputs = listOf(
                BtcReviewInput(0, first, 20_000L, true, owned, 0xFFFFFFFDL),
                BtcReviewInput(1, second, 20_000L, true, owned, 0xFFFFFFFDL, riskMarked = true),
            ),
        )
        val review = reviewOf(mixed)
        assertEquals(
            listOf(Danger.DANGER to "bitcoin.risk-marked", Danger.CAUTION to "bitcoin.risk-unchecked"),
            review.warnings.map { it.level to it.code },
        )
        assertEquals(FieldPath("bitcoin.input.1.amount"), review.warnings.first().field)
    }

    @Test
    fun everyMarkedInputIsListedWithItsOutPointAndOneTickNamesThemAll() {
        val two = request(feeSats = 10_000L).copy(
            inputs = listOf(
                BtcReviewInput(0, first, 20_000L, true, owned, 0xFFFFFFFDL, riskMarked = true),
                BtcReviewInput(1, second, 20_000L, true, owned, 0xFFFFFFFDL, riskMarked = true),
            ),
        )
        val review = reviewOf(two)
        val rows = review.warnings.filter { it.code == "bitcoin.risk-marked" }
        assertTrue(rows[0].message.contains(firstText))
        assertTrue(rows[1].message.contains(secondText))
        val check = "I understand spending 2 marked input(s) may burn any inscription or rune they carry: " +
            "${firstText}, ${secondText}."
        assertEquals(listOf(check, check), rows.map { it.acknowledge })
        assertEquals(listOf(check), review.checkLabels())
        assertNull(Approval.of(review, emptySet()))
        assertNotNull(Approval.of(review, setOf(check)))
    }

    @Test
    fun aTickForOneMarkedSetDoesNotCoverAnotherSet() {
        val one = request(feeSats = 10_000L).copy(
            inputs = listOf(BtcReviewInput(0, first, 20_000L, true, owned, 0xFFFFFFFDL, riskMarked = true)),
        )
        val two = one.copy(inputs = one.inputs + BtcReviewInput(1, second, 20_000L, true, owned, 0xFFFFFFFDL, riskMarked = true))
        val ticked = reviewOf(one).checkLabels().toSet()
        assertNotNull(Approval.of(reviewOf(one), ticked))
        assertNull(Approval.of(reviewOf(two), ticked))
    }

    @Test
    fun theRiskWarningsSayWhatTheMarkMeansAndWhatIsNotChecked() {
        assertEquals(
            "Input 3 (${secondText}) is marked as carrying an inscription or rune. Spending it may burn it.",
            warningText(BtcWarning.RiskMarkedInput(3, second)),
        )
        assertEquals("Inputs were not checked for inscriptions or runes.", warningText(BtcWarning.RiskUnchecked))
    }
}
