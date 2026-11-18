package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaNonce
import app.lensly.core.solana.SolanaTokenAccounts
import app.lensly.core.wallet.Mnemonic
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.send.solana.SolanaAssets
import app.lumio.send.solana.SolanaOutcome
import app.lumio.send.solana.SolanaSend
import app.lumio.send.solana.SolanaSendAccount
import app.lumio.send.solana.SolanaSendAsset
import app.lumio.send.solana.SolanaSendInput
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.send.solana.SolanaSplSend
import app.lumio.ui.send.solana.SolanaSendFormScreen
import app.lumio.ui.send.solana.SolanaSendReviewScreen
import app.lumio.ui.send.solana.SolanaSetupReviewScreen
import app.lumio.ui.send.solana.SolanaSignRequestScreen
import app.lumio.ui.send.solana.solanaOutcomeText
import app.lumio.ui.send.solanaSummary
import fr.acinq.bitcoin.Base58
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

    @Test
    fun solanaSendForm() = compose.lumioSnapshot("solana_send_form") {
        SolanaSendFormScreen(
            network = SOLANA_DEVNET,
            sender = sender,
            assets = listOf(solAsset, usdcAsset),
            selected = solAsset,
            onSelect = {},
            balanceText = "${SolanaSendInput.format(balance)} SOL",
            recipient = "",
            onRecipient = {},
            onScan = {},
            amount = "1.5",
            onAmount = {},
            maxEnabled = true,
            onMax = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun solanaSendFormToken() = compose.lumioSnapshot("solana_send_form_token") {
        SolanaSendFormScreen(
            network = SOLANA_DEVNET,
            sender = sender,
            assets = listOf(solAsset, usdcAsset),
            selected = usdcAsset,
            onSelect = {},
            balanceText = "${SolanaAssets.text(usdc.amount, usdcAsset.decimals)} USDC",
            recipient = "",
            onRecipient = {},
            onScan = {},
            amount = "1.5",
            onAmount = {},
            maxEnabled = true,
            onMax = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun solanaSendReview() = compose.lumioSnapshot("solana_send_review") {
        SolanaSendReviewScreen(plan = plan, symbol = "SOL", notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun solanaSendReviewToken() = compose.lumioSnapshot("solana_send_review_token") {
        SolanaSendReviewScreen(plan = tokenPlan, symbol = "USDC", notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun solanaSignRequest() = compose.lumioSnapshot("solana_send_request") {
        SolanaSignRequestScreen(parts = SolanaSend.requestParts(plan), notice = null, onScan = {}, onBack = {})
    }

    @Test
    fun solanaSendSent() = compose.lumioSnapshot("solana_send_sent") {
        SubmitFixture(
            sentSubmit("B".repeat(88)),
            solanaSummary(plan),
            explorerUrl = { SolanaSend.explorerTxUrl(SOLANA_DEVNET, it) },
            status = solanaOutcomeText(SOLANA_DEVNET, SolanaOutcome.CONFIRMED),
        )
    }

    @Test
    fun solanaSetupReview() = compose.lumioSnapshot("solana_setup_review") {
        SolanaSetupReviewScreen(
            network = SOLANA_DEVNET,
            sender = sender,
            nonceAccount = Base58.encode(SolanaNonce.accountOf(senderKey)),
            rentLamports = 1_447_680L,
            notice = null,
            onConfirm = {},
            onBack = {},
        )
    }
}
