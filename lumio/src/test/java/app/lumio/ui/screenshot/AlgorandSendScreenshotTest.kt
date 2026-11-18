package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.send.algorand.AlgorandAsset
import app.lumio.send.algorand.AlgorandAssets
import app.lumio.send.algorand.AlgorandFunds
import app.lumio.send.algorand.AlgorandReviewScreen
import app.lumio.send.algorand.AlgorandSend
import app.lumio.send.algorand.AlgorandSendFormScreen
import app.lumio.send.algorand.AlgorandSignRequestScreen
import app.lumio.send.algorand.AsaSend
import app.lumio.ui.send.algorandSummary
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Algorand send screens as goldens: one dark-theme golden per screen (docs/testing/guideline.md 1.5). The plans are built from fixed public values, so
 * the goldens do not depend on any key or any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
class AlgorandSendScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val signer = ByteArray(32) { 7 }
    private val sender = AlgoAddress.encode(signer)
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
    private val usdc = 10458941UL
    private val algo = AlgorandAssets.native
    private val usdcHeld = AlgorandAsset(usdc, "USDC", 6, BigInteger("2500000000"))
    private val usdcNotOptedIn = AlgorandAsset(usdc, "USDC", 6, null)
    private val plan = AlgorandSend.plan(
        network = ALGORAND_TESTNET,
        funds = funds,
        fingerprint = 0x1234_5678L,
        accountIndex = 0,
        signer = signer,
        to = recipient,
        amount = BigInteger.valueOf(1_500_000L),
    )
    private val assetPlan = AsaSend.plan(
        network = ALGORAND_TESTNET,
        funds = funds,
        fingerprint = 0x1234_5678L,
        accountIndex = 0,
        signer = signer,
        to = recipient,
        assetId = usdc,
        amount = BigInteger("1500000"),
        held = BigInteger("2500000000"),
        recipientOptedIn = true,
    )
    private val optInPlan = AsaSend.optIn(ALGORAND_TESTNET, funds, fingerprint = 0x1234_5678L, accountIndex = 0, signer = signer, assetId = usdc)
    private val txid = AlgoSignedTxn.txid(plan.txnBytes)

    @Test
    fun algorandSendForm() = compose.lumioSnapshot("algorand_send_form") {
        AlgorandSendFormScreen(
            network = ALGORAND_TESTNET,
            fromAddress = sender,
            assets = listOf(algo, usdcHeld),
            selected = algo,
            onSelect = {},
            balanceText = AlgorandFormat.amount(funds.balance),
            recipient = recipientAddress,
            onRecipient = {},
            onScan = {},
            amount = "1.5",
            onAmount = {},
            maxEnabled = true,
            onMax = {},
            feeText = AlgorandFormat.amount(BigInteger.valueOf(1_000L)),
            amountError = null,
            busy = false,
            notice = null,
            canReview = true,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendFormAsset() = compose.lumioSnapshot("algorand_send_form_asset") {
        AlgorandSendFormScreen(
            network = ALGORAND_TESTNET,
            fromAddress = sender,
            assets = listOf(algo, usdcHeld),
            selected = usdcHeld,
            onSelect = {},
            balanceText = "2500 USDC",
            recipient = recipientAddress,
            onRecipient = {},
            onScan = {},
            amount = "1.5",
            onAmount = {},
            maxEnabled = true,
            onMax = {},
            feeText = null,
            amountError = null,
            busy = false,
            notice = null,
            canReview = true,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendFormOptIn() = compose.lumioSnapshot("algorand_send_form_opt_in") {
        AlgorandSendFormScreen(
            network = ALGORAND_TESTNET,
            fromAddress = sender,
            assets = listOf(algo, usdcNotOptedIn),
            selected = usdcNotOptedIn,
            onSelect = {},
            balanceText = null,
            recipient = "",
            onRecipient = {},
            onScan = {},
            amount = "",
            onAmount = {},
            maxEnabled = false,
            onMax = {},
            feeText = null,
            amountError = null,
            busy = false,
            notice = null,
            canReview = true,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendFormAmountError() = compose.lumioSnapshot("algorand_send_form_amount_error") {
        AlgorandSendFormScreen(
            network = ALGORAND_TESTNET,
            fromAddress = sender,
            assets = listOf(algo, usdcHeld),
            selected = algo,
            onSelect = {},
            balanceText = AlgorandFormat.amount(funds.balance),
            recipient = recipientAddress,
            onRecipient = {},
            onScan = {},
            amount = "3",
            onAmount = {},
            maxEnabled = true,
            onMax = {},
            feeText = AlgorandFormat.amount(BigInteger.valueOf(1_000L)),
            amountError = "More than you can send. The most is 1.899 ALGO.",
            busy = false,
            notice = null,
            canReview = false,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun algorandSendReview() = compose.lumioSnapshot("algorand_send_review") {
        AlgorandReviewScreen(plan = plan, symbol = "ALGO", decimals = 6, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun algorandSendReviewAsset() = compose.lumioSnapshot("algorand_send_review_asset") {
        AlgorandReviewScreen(plan = assetPlan, symbol = "USDC", decimals = 6, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun algorandSendReviewOptIn() = compose.lumioSnapshot("algorand_send_review_opt_in") {
        AlgorandReviewScreen(plan = optInPlan, symbol = "USDC", decimals = 6, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun algorandSendSignRequest() = compose.lumioSnapshot("algorand_send_sign_request") {
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
