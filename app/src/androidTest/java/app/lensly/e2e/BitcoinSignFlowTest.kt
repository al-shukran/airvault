package app.lensly.e2e

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.app.isolated
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.flow.SignStep
import fr.acinq.bitcoin.Satoshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val NETWORK = BtcNetwork.TESTNET4
private val SCRIPT = ScriptType.NATIVE_SEGWIT

/**
 * The Bitcoin signing flow, end to end, on a device: a testnet4 native segwit account is added to the test wallet, and a `ur:crypto-psbt`
 * that spends one of its outputs is fed to the scanner through the real [app.lensly.ui.flow.SignWalletRoute]. The review shows the amount
 * sent and the recipient, the owner approves it with the App Password, and the signed PSBT that comes back is finalized and run through
 * bitcoin-kmp's script interpreter, which is the check a node makes. A changed output must then fail it.
 */
@RunWith(AndroidJUnit4::class)
class BitcoinSignFlowTest : SignFlowE2eBase() {

    @Test
    fun bitcoinSpendIsReviewedApprovedAndAnsweredWithAPsbtThatVerifies() {
        val wallet = openWallet { store, id -> store.addBitcoinAccounts(id, SCRIPT, NETWORK, 1, BulkCap.DEFAULT.accounts) }
        val account = BtcAccount.fromSeed(E2E_SEED, NETWORK, SCRIPT)
        val foreign = BtcAccount.fromSeed(ByteArray(16) { 9 }, NETWORK, SCRIPT)

        // The payment goes to an address of another account, so the review shows it as an outside recipient. The change comes back to
        // change index 0 of this account. Inputs total 200,000 sats, the outputs 195,000, so the fee is 5,000.
        val payment = BtcFixtures.paying(foreign, paid = 150_000L, change = 0L).first()
        val change = BtcFixtures.paying(account, paid = 0L, change = 45_000L).last()
        val recipient = foreign.receiveAddress(5)
        val spends = listOf(BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 200_000L), account, 0, 0))
        val psbt = BtcFixtures.psbt(spends, listOf(payment, change))

        val driver = driverFor(wallet, BtcPsbt.encodeUrParts(psbt))
        driver.open()
        val ready = (driver.awaitReview { it is SignStep.AppReview } as SignStep.AppReview).ready
        val facts = checkNotNull(ready.bitcoin)

        assertEquals(5_000L, facts.feeSats)
        assertEquals(recipient, facts.outputs[0].address)
        assertFalse(facts.outputs[0].isOwnChange)
        assertTrue(facts.outputs[1].isOwnChange)

        // The summary sentence names the amount sent and the recipient's full address: a shortened one could pass a look-alike (R26).
        compose.onNodeWithText("Send 0.0015 BTC from ${isolated(ready.signer.name)} to $recipient?").assertExists()
        compose.onNodeWithText("Bitcoin Testnet4").assertExists()
        driver.readToTheEnd()
        assertTrue(driver.approvable())

        val parts = driver.approveAndSign { it is SignStep.AppConfirming }
        val signed = BtcPsbt.fromUr(urOfParts(parts, "crypto-psbt"))
        val tx = BtcFixtures.finalize(signed, spends)
        val prevouts = BtcFixtures.prevouts(spends)
        assertTrue("the signed transaction must pass the script interpreter", BtcFixtures.verifies(tx, prevouts))

        // A negative control: an output changed after signing must fail, so the check above means something.
        val tampered = tx.copy(txOut = tx.txOut.mapIndexed { i, out -> if (i == 0) out.copy(amount = Satoshi(150_001L)) else out })
        assertFalse("a changed output still verifies", BtcFixtures.verifies(tampered, prevouts))
    }
}
