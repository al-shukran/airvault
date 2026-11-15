package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.btc.BtcSigner
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import com.sparrowwallet.hummingbird.URDecoder
import fr.acinq.bitcoin.Satoshi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

            val answer = URDecoder.decode(BtcSigner(account).sign(plan.psbt).ur)
            val tx = BtcSend.signed(plan, answer)
            val prevouts = listOf(coin.previous.txOut[0])
            assertTrue(BtcFixtures.verifies(tx, prevouts), "${type.name}: the interpreter rejects the signed transaction")
            assertEquals(plan.tx.txOut, tx.txOut, type.name)
            assertEquals(plan.tx.txIn.map { it.outPoint }, tx.txIn.map { it.outPoint }, type.name)
            assertEquals(plan.inputSats - plan.tx.txOut.sumOf { it.amount.sat }, plan.feeSats, type.name)

            // Lensly's own review reads the request as the plan made it: verified amounts, the same fee, and the change it expects.
            val review = BtcSigner(account).review(plan.psbt)
            assertTrue(review.allInputsVerified, type.name)
            assertEquals(plan.feeSats, review.feeSats, type.name)
            assertEquals(plan.changeIndex, review.outputs.single { it.isOwnChange }.changeIndex, type.name)
            assertEquals(plan.recipient, review.outputs[0].address, type.name)

            // Negative control: an output changed after signing fails the interpreter, so the check above means something.
            val tampered = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(amount = Satoshi(out.amount.sat + 1)) else out })
            assertFalse(BtcFixtures.verifies(tampered, prevouts), "${type.name}: a changed output still verifies")
        }
    }

    @Test
    fun aSignedAnswerForAnotherRequestIsRefused() {
        val (account, coin) = funded(ScriptType.NATIVE_SEGWIT)
        val plan = planOf(account, coin, amount = 30_000L)
        val other = planOf(account, coin, amount = 31_000L)
        val answer = URDecoder.decode(BtcSigner(account).sign(other.psbt).ur)
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
        val accounts = BtcWatchOnly.accounts(PairFixtures.seed, testnet4)
        val refusal = assertFailsWith<IllegalArgumentException> {
            BtcSend.signed(plan, URDecoder.decode(BtcWatchOnly.cryptoAccount(accounts)))
        }
        assertEquals("This code is not a signed request from Lensly.", refusal.message)
    }
}
