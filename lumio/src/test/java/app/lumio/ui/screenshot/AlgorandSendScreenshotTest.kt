package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.airvault.chains.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.algorand.AlgorandFormat
import app.lumio.send.algorand.AlgorandAsset
import app.lumio.send.algorand.AlgorandAssets
import app.lumio.send.algorand.AlgorandFunds
import app.lumio.send.algorand.AlgorandSend
import app.lumio.send.algorand.AsaSend
import app.lensly.ui.components.groupAddress
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendReviewPage
import app.lumio.ui.send.SendShowPage
import app.lumio.ui.send.SummaryRow
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Algorand send screens as goldens: one dark-theme golden per screen (docs/testing/guideline.md 1.5). The plans are built from fixed public values, so
 * the goldens do not depend on any key or any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        SendAmountPage(
            symbol = "ALGO",
            toTitle = groupAddress(recipientAddress),
            toDetail = "",
            toAddress = recipientAddress,
            fromTitle = "Account 1",
            fromDetail = "${AlgorandFormat.amount(funds.balance)} available",
            asset = null,
            onAsset = null,
            amount = "1.5",
            onAmount = {},
            amountError = null,
            leaves = null,
            onUseMax = {},
            networkName = "Algorand Testnet",
            feeText = AlgorandFormat.amount(BigInteger.valueOf(1_000L)),
            onFee = null,
            note = "${AlgorandSend.algoText(funds.minimum)} ALGO stays reserved",
            canReview = true,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendFormAsset() = compose.lumioSnapshot("algorand_send_form_asset") {
        SendAmountPage(
            symbol = "USDC",
            toTitle = groupAddress(recipientAddress),
            toDetail = "",
            toAddress = recipientAddress,
            fromTitle = "Account 1",
            fromDetail = "2500 USDC available",
            asset = "USDC",
            onAsset = {},
            amount = "1.5",
            onAmount = {},
            amountError = null,
            leaves = null,
            onUseMax = {},
            networkName = "Algorand Testnet",
            feeText = "Shown on review",
            onFee = null,
            note = null,
            canReview = true,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendFormAmountError() = compose.lumioSnapshot("algorand_send_form_amount_error") {
        SendAmountPage(
            symbol = "ALGO",
            toTitle = groupAddress(recipientAddress),
            toDetail = "",
            toAddress = recipientAddress,
            fromTitle = "Account 1",
            fromDetail = "${AlgorandFormat.amount(funds.balance)} available",
            asset = null,
            onAsset = null,
            amount = "3",
            onAmount = {},
            amountError = "More than you can send. The most is 1.899 ALGO.",
            leaves = null,
            onUseMax = {},
            networkName = "Algorand Testnet",
            feeText = AlgorandFormat.amount(BigInteger.valueOf(1_000L)),
            onFee = null,
            note = "${AlgorandSend.algoText(funds.minimum)} ALGO stays reserved",
            canReview = false,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendReview() = compose.lumioSnapshot("algorand_send_review") {
        SendReviewPage(
            heading = "Send 1.5 ALGO from Account 1 to ${groupAddress(recipientAddress)}?",
            toTitle = groupAddress(recipientAddress),
            toDetail = "",
            toAddress = recipientAddress,
            validLine = "Address is valid on Algorand Testnet",
            firstTime = "First time sending here. Compare every group with where you got it.",
            rows = listOf(
                SummaryRow("Amount", AlgorandAssets.amountText(plan, "ALGO", 6)),
                SummaryRow("Network Fee", AlgorandFormat.amount(plan.fee)),
                SummaryRow("Network", "Algorand Testnet"),
                SummaryRow("From", groupAddress(sender)),
            ),
            warnings = emptyList(),
            notice = null,
            onShow = {},
            onEdit = {},
            backLabel = "Amount",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendReviewAsset() = compose.lumioSnapshot("algorand_send_review_asset") {
        SendReviewPage(
            heading = "Send 1.5 USDC from Account 1 to ${groupAddress(recipientAddress)}?",
            toTitle = groupAddress(recipientAddress),
            toDetail = "",
            toAddress = recipientAddress,
            validLine = "Address is valid on Algorand Testnet",
            firstTime = null,
            rows = listOf(
                SummaryRow("Amount", AlgorandAssets.amountText(assetPlan, "USDC", 6)),
                SummaryRow("Network Fee", AlgorandFormat.amount(assetPlan.fee)),
                SummaryRow("Network", "Algorand Testnet"),
                SummaryRow("From", groupAddress(sender)),
            ),
            warnings = emptyList(),
            notice = null,
            onShow = {},
            onEdit = {},
            backLabel = "Amount",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendReviewOptIn() = compose.lumioSnapshot("algorand_send_review_opt_in") {
        SendReviewPage(
            heading = "Opt In to USDC (ASA $usdc)?",
            toTitle = "Account 1",
            toDetail = "Your account",
            toAddress = optInPlan.fromAddress,
            validLine = "Address is valid on Algorand Testnet",
            firstTime = null,
            rows = listOf(
                SummaryRow("Asset", "USDC"),
                SummaryRow("Network Fee", AlgorandFormat.amount(optInPlan.fee)),
                SummaryRow("Network", "Algorand Testnet"),
                SummaryRow("From", groupAddress(sender)),
            ),
            warnings = emptyList(),
            notice = null,
            onShow = {},
            onEdit = {},
            backLabel = "Account",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendSignRequest() = compose.lumioSnapshot("algorand_send_sign_request") {
        SendShowPage(
            parts = AlgorandSend.requestParts(plan, ByteArray(16) { 1 }),
            onNext = {},
            onCancel = {},
            backLabel = "Review",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun algorandSendSent() = compose.lumioSnapshot("algorand_send_sent") {
        SubmitFixture(
            machine = sentSubmit(txid),
            headline = "Your ALGO Is on Its Way",
            amount = "1.5 ALGO",
            recipient = groupAddress(recipientAddress),
            network = "Algorand Testnet",
            timing = "Waiting for the network to confirm the transaction.",
            explorerUrl = { AlgorandSend.explorerUrl(ALGORAND_TESTNET, it) },
        )
    }
}
