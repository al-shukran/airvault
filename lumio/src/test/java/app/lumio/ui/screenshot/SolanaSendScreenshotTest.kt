package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaNonce
import app.lensly.core.solana.SolanaTokenAccounts
import app.lensly.core.wallet.Mnemonic
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.send.solana.SolanaAssets
import app.lumio.send.solana.SolanaSend
import app.lumio.send.solana.SolanaSendAccount
import app.lumio.send.solana.SolanaSendAsset
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.send.solana.SolanaSplSend
import fr.acinq.bitcoin.Base58
import app.lensly.ui.components.groupAddress
import app.lumio.send.solana.SETUP_SIGN_TEXT
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

/** The Solana send screens, and the one-time setup of offline signing, with fixed public data, as goldens. The account is the 24-word BIP39 test vector's account 0. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SolanaSendScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val seed = Mnemonic.of(("abandon ".repeat(23) + "art").split(' ')).use { it.deriveSeed("") }
    private val senderKey = SolanaAccount.publicKey(seed, 0)
    private val sender = SolanaAccount.addressOf(senderKey)
    private val account = SolanaSendAccount(0, senderKey, 0x0BADF00D)
    private val recipient = Base58.decode("7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr")
    private val nonce = Base58.decode("3K2oo9odsrvknS7gMzJ4eLBnjd3UG14yPuJc8DiviJqY")
    private val balance = BigInteger.valueOf(2_000_000_000L)
    private val usdc = SplBalance(
        account = sender,
        mint = Base58.encode(ByteArray(32) { 3 }),
        program = SolanaTokenAccounts.TOKEN_PROGRAM,
        amount = BigInteger("25000000"),
        decimals = 6,
    )
    private val solAsset = SolanaAssets.native
    private val usdcAsset = SolanaSendAsset(usdc, "USDC", 6)
    private val plan: SolanaSendPlan = SolanaSend.plan(
        network = SOLANA_DEVNET,
        account = account,
        recipient = recipient,
        lamports = 1_500_000_000L,
        balance = balance,
        nonceValue = nonce,
        requestId = ByteArray(16) { (it + 1).toByte() },
    )
    private val tokenPlan: SolanaSendPlan = SolanaSplSend.plan(
        network = SOLANA_DEVNET,
        account = account,
        recipient = recipient,
        balance = usdc,
        amount = 1_500_000UL,
        sol = balance,
        nonceValue = nonce,
        requestId = ByteArray(16) { (it + 1).toByte() },
        recipientAccountExists = true,
        rentLamports = 2_039_280L,
    )

    private val recipientText = Base58.encode(recipient)

    @Test
    fun solanaSendForm() = compose.lumioSnapshot("solana_send_form") {
        SendAmountPage(
            symbol = "SOL",
            toTitle = groupAddress(recipientText),
            toDetail = "",
            toAddress = recipientText,
            fromTitle = "Account 1",
            fromDetail = "${SolanaSendInput.format(balance)} SOL available",
            asset = null,
            onAsset = null,
            amount = "1.5",
            onAmount = {},
            amountError = null,
            leaves = null,
            onUseMax = {},
            networkName = "Solana Devnet",
            feeText = "0.000005 SOL",
            onFee = null,
            note = "Chosen for you: Solana Devnet, the network this account sends on.",
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
    fun solanaSendFormToken() = compose.lumioSnapshot("solana_send_form_token") {
        SendAmountPage(
            symbol = "USDC",
            toTitle = groupAddress(recipientText),
            toDetail = "",
            toAddress = recipientText,
            fromTitle = "Account 1",
            fromDetail = "${SolanaAssets.text(usdc.amount, usdcAsset.decimals)} USDC available",
            asset = "USDC",
            onAsset = {},
            amount = "1.5",
            onAmount = {},
            amountError = null,
            leaves = null,
            onUseMax = {},
            networkName = "Solana Devnet",
            feeText = "0.000005 SOL",
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
    fun solanaSendReview() = compose.lumioSnapshot("solana_send_review") {
        SendReviewPage(
            heading = "Send 1.5 SOL from Account 1 to ${groupAddress(recipientText)}?",
            toTitle = groupAddress(recipientText),
            toDetail = "",
            toAddress = recipientText,
            validLine = "Address is valid on Solana Devnet",
            firstTime = "First time sending here. Compare every group with where you got it.",
            rows = listOf(
                SummaryRow("Amount", "1.5 SOL"),
                SummaryRow("Network Fee", "0.000005 SOL"),
                SummaryRow("Network", "Solana Devnet"),
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
    fun solanaSendReviewToken() = compose.lumioSnapshot("solana_send_review_token") {
        SendReviewPage(
            heading = "Send 1.5 USDC from Account 1 to ${groupAddress(recipientText)}?",
            toTitle = groupAddress(recipientText),
            toDetail = "",
            toAddress = recipientText,
            validLine = "Address is valid on Solana Devnet",
            firstTime = null,
            rows = listOf(
                SummaryRow("Amount", "1.5 USDC"),
                SummaryRow("Network Fee", "0.000005 SOL"),
                SummaryRow("Network", "Solana Devnet"),
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
    fun solanaSignRequest() = compose.lumioSnapshot("solana_send_request") {
        SendShowPage(
            parts = SolanaSend.requestParts(plan),
            onNext = {},
            onCancel = {},
            backLabel = "Review",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun solanaSendSent() = compose.lumioSnapshot("solana_send_sent") {
        SubmitFixture(
            machine = sentSubmit("B".repeat(88)),
            headline = "Your SOL Is on Its Way",
            amount = "1.5 SOL",
            recipient = groupAddress(recipientText),
            network = "Solana Devnet",
            timing = "Confirmed on Solana Devnet.",
            explorerUrl = { SolanaSend.explorerTxUrl(SOLANA_DEVNET, it) },
        )
    }

    @Test
    fun solanaSetupReview() = compose.lumioSnapshot("solana_setup_review") {
        SendReviewPage(
            heading = "Offline Signing",
            toTitle = "Nonce account",
            toDetail = "Created once for this account",
            toAddress = Base58.encode(SolanaNonce.accountOf(senderKey)),
            validLine = "Create the nonce account for this account. You do this once.",
            firstTime = SETUP_SIGN_TEXT,
            rows = listOf(
                SummaryRow("Rent", "${SolanaSendInput.format(BigInteger.valueOf(1_447_680L))} SOL"),
                SummaryRow("Network Fee", "0.000005 SOL"),
                SummaryRow("Network", "Solana Devnet"),
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
}
