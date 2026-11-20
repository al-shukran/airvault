package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.toReview
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import fr.acinq.bitcoin.Satoshi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The Lumio request, Lensly's signature and Lumio's finalization, end to end: each Script Type's request is reviewed and signed by the
 * Lensly signer from :shared with the test seed, and the answer is finalized into a transaction the interpreter accepts.
 */
class BtcSendRoundTripTest {
    private val testnet4 = BtcNetwork.TESTNET4

    /** The account of [type] on testnet4 with the test seed, and a coin of 100,000 sat at its receive address 0. */
    private fun funded(type: ScriptType): Pair<BtcAccount, BtcCoin> {
        val account = BtcAccount.fromSeed(PairFixtures.seed, testnet4, type)
        return account to BtcCoin(BtcFixtures.funding(account, 0, 0, 100_000L), 0, 0, 0)
    }

    /** A request of [amount] from [coin] to receive address 5 of account 1, with change to change address 0, at 2 sat/vB. */
    private fun planOf(account: BtcAccount, coin: BtcCoin, amount: Long = 30_000L): BtcSendPlan {
        val recipient = BtcAccount.fromSeed(PairFixtures.seed, testnet4, account.scriptType, accountIndex = 1).receiveAddress(5)
        return BtcSend.plan(account.watchOnly(), BtcSpendable(listOf(coin), nextChangeIndex = 0), recipient, amount, 2.0)
    }

    @Test
    fun everyScriptTypeIsSignedByLenslyAndFinalizedToATransactionTheInterpreterAccepts() {
        for (type in ScriptType.entries) {
            val (account, coin) = funded(type)
            val plan = planOf(account, coin)
            assertTrue(BtcPsbt.hasCompanionMarker(plan.psbt), type.name)

            val answer = URDecoder.decode(BtcFixtures.signerOf(account, PairFixtures.seed).sign(plan.psbt).ur)
            val tx = BtcSend.signed(plan, answer)
            val prevouts = listOf(coin.previous.txOut[0])
            assertTrue(BtcFixtures.verifies(tx, prevouts), "${type.name}: the interpreter rejects the signed transaction")
            assertEquals(plan.tx.txOut, tx.txOut, type.name)
            assertEquals(plan.tx.txIn.map { it.outPoint }, tx.txIn.map { it.outPoint }, type.name)
            assertEquals(plan.inputSats - plan.tx.txOut.sumOf { it.amount.sat }, plan.feeSats, type.name)

            // Lensly's own review reads the request as the plan made it: verified amounts, the same fee, and the change it expects.
            val review = BtcFixtures.signerOf(account, PairFixtures.seed).review(plan.psbt)
            assertTrue(review.allInputsVerified, type.name)
            assertEquals(plan.feeSats, review.feeSats, type.name)
            assertEquals(plan.changeIndex, review.outputs.single { it.isOwnChange }.changeIndex, type.name)
            assertEquals(plan.recipient, review.outputs[0].address, type.name)

            // Negative control: an output changed after signing fails the interpreter, so the check above means something.
            val tampered = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(amount = Satoshi(out.amount.sat + 1)) else out })
            assertFalse(BtcFixtures.verifies(tampered, prevouts), "${type.name}: a changed output still verifies")
        }
    }

    /** Lensly's review of [request] as the app shows it: testnet4, Native Segwit, and the account's first receive address as the sender. */
    private fun reviewOf(request: BtcReview, account: BtcAccount): Review = request.toReview(
        chain = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae"),
        network = NetworkLabel(name = "Bitcoin Testnet4", testnet = true, detail = null),
        scriptType = "Native Segwit P2WPKH",
        from = account.receiveAddress(0),
    )

    @Test
    fun aMarkedInputIsMarkedInTheRequestKeptBySigningAndReachesLenslyAsADanger() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, BtcCoin(coin.previous, 0, 0, 0, riskMarked = true))
        assertEquals(listOf(BtcPsbt.RISK_MARK), plan.psbt.inputs.single().unknown)

        val signer = BtcFixtures.signerOf(account, PairFixtures.seed)
        val review = reviewOf(signer.review(plan.psbt), account)
        assertEquals(Danger.DANGER, review.warnings.single { it.code == "bitcoin.risk-marked" }.level)
        assertNull(Approval.of(review, emptySet()), "the owner ticks the mark before the request signs")

        val answer = URDecoder.decode(signer.sign(plan.psbt).ur)
        assertEquals(listOf(BtcPsbt.RISK_MARK), BtcPsbt.fromUr(answer).inputs.single().unknown)
        assertTrue(BtcFixtures.verifies(BtcSend.signed(plan, answer), listOf(coin.previous.txOut[0])))
    }

    @Test
    fun anUnmarkedInputIsOnlyACautionForLensly() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, coin)
        assertEquals(emptyList(), plan.psbt.inputs.single().unknown)
        val review = reviewOf(BtcFixtures.signerOf(account, PairFixtures.seed).review(plan.psbt), account)
        assertTrue(review.warnings.none { it.level == Danger.DANGER })
        assertTrue(review.warnings.any { it.code == "bitcoin.risk-unchecked" && it.level == Danger.CAUTION })
    }

    @Test
    fun aSignedAnswerForAnotherRequestIsRefused() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, coin, amount = 30_000L)
        val other = planOf(account, coin, amount = 31_000L)
        val answer = URDecoder.decode(BtcFixtures.signerOf(account, PairFixtures.seed).sign(other.psbt).ur)
        val refusal = assertFailsWith<IllegalArgumentException> { BtcSend.signed(plan, answer) }
        assertEquals("This signature is for a different transaction.", refusal.message)
    }

    @Test
    fun anUnsignedRequestIsRefusedAsAnAnswer() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, coin)
        assertFailsWith<IllegalArgumentException> { BtcSend.signed(plan, URDecoder.decode(BtcPsbt.encodeUr(plan.psbt))) }
    }

    @Test
    fun aUrThatIsNotAPsbtIsRefused() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, coin)
        val other = UREncoder.encode(UR("eth-signature", byteArrayOf(0x01, 0x02)))
        val refusal = assertFailsWith<IllegalArgumentException> {
            BtcSend.signed(plan, URDecoder.decode(other))
        }
        assertEquals("This code is not a signed request from Lensly.", refusal.message)
    }
}
