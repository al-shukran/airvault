package app.lumio.ui.screenshot

import app.lumio.ui.send.algorandSummary
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lensly.ui.qr.awaitQrCodes
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.send.algorand.AlgorandAmountScreen
import app.lumio.send.algorand.AlgorandFunds
import app.lumio.send.algorand.AlgorandRecipientScreen
import app.lumio.send.algorand.AlgorandReviewScreen
import app.lumio.send.algorand.AlgorandSend
import app.lumio.send.algorand.AlgorandSignRequestScreen
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Algorand send screens as goldens: one dark-theme golden per screen (docs/testing/guideline.md 1.5). The plan is built from fixed
 * public values, so the goldens do not depend on any key or any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
class AlgorandSendScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val signer = ByteArray(32) { 7 }
    private val recipient = ByteArray(32) { 0x42 }
    private val recipientAddress = AlgoAddress.encode(recipient)
    private val funds = AlgorandFunds(
        balance = BigInteger.valueOf(2_000_000L),
        params = AlgorandSuggestedParams(
            genesisId = "testnet-v1.0",
            genesisHash = ByteArray(32) { 3 },
            lastRound = 61_000_000L,
            feePerByte = 0L,
            minFee = 1_000L,
        ),
    )
    private val plan = AlgorandSend.plan(
        network = ALGORAND_TESTNET,
        funds = funds,
        fingerprint = 0x1234_5678L,
        accountIndex = 0,
        signer = signer,
        to = recipient,
        amount = BigInteger.valueOf(1_500_000L),
    )
    private val txid = AlgoSignedTxn.txid(plan.txnBytes)

    @Test
    fun algorandSendRecipient() = compose.lumioSnapshot("algorand_send_recipient") {
        AlgorandRecipientScreen(
            network = ALGORAND_TESTNET,
            recipient = recipientAddress,
            onRecipient = {},
            onScan = {},
            onContinue = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendAmount() = compose.lumioSnapshot("algorand_send_amount") {
        AlgorandAmountScreen(
            network = ALGORAND_TESTNET,
            toAddress = recipientAddress,
            balance = funds.balance,
            feeEstimate = BigInteger.valueOf(1_000L),
            maxEnabled = true,
            amount = "1.5",
            onAmount = {},
            onMax = {},
            error = null,
            notice = null,
            canReview = true,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendAmountError() = compose.lumioSnapshot("algorand_send_amount_error") {
        AlgorandAmountScreen(
            network = ALGORAND_TESTNET,
            toAddress = recipientAddress,
            balance = funds.balance,
            feeEstimate = BigInteger.valueOf(1_000L),
            maxEnabled = true,
            amount = "3",
            onAmount = {},
            onMax = {},
            error = "More than you can send. The most is 1.899 ALGO.",
            notice = null,
            canReview = false,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendReview() = compose.lumioSnapshot("algorand_send_review") {
        AlgorandReviewScreen(plan = plan, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun algorandSendSignRequest() = compose.lumioSnapshot("algorand_send_sign_request", beforeCapture = { awaitQrCodes() }) {
        AlgorandSignRequestScreen(
            network = ALGORAND_TESTNET,
            parts = AlgorandSend.requestParts(plan, ByteArray(16) { 1 }),
            notice = null,
            onScan = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendSent() = compose.lumioSnapshot("algorand_send_sent") {
        SubmitFixture(
            sentSubmit(txid),
            algorandSummary(plan),
            explorerUrl = { AlgorandSend.explorerUrl(ALGORAND_TESTNET, it) },
            status = "Waiting for the network to confirm the transaction.",
        )
    }
}
