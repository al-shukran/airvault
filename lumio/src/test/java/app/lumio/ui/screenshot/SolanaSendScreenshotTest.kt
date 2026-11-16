package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.send.solana.SolanaOutcome
import app.lumio.send.solana.SolanaSend
import app.lumio.send.solana.SolanaSendAccount
import app.lumio.send.solana.SolanaSendPlan
import app.lumio.ui.send.solana.SolanaSendFormScreen
import app.lumio.ui.send.solana.SolanaSendReviewScreen
import app.lumio.ui.send.solana.SolanaSendSentScreen
import app.lumio.ui.send.solana.SolanaSignRequestScreen
import fr.acinq.bitcoin.Base58
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four Solana send screens with fixed public data, as goldens. The account is the 24-word BIP39 test vector's account 0. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SolanaSendScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val seed = Mnemonic.of(("abandon ".repeat(23) + "art").split(' ')).use { it.deriveSeed("") }
    private val senderKey = SolanaAccount.publicKey(seed, 0)
    private val sender = SolanaAccount.addressOf(senderKey)
    private val balance = BigInteger.valueOf(2_000_000_000L)
    private val plan: SolanaSendPlan = SolanaSend.plan(
        network = SOLANA_DEVNET,
        account = SolanaSendAccount(0, senderKey, 0x0BADF00D),
        recipient = Base58.decode("7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr"),
        lamports = 1_500_000_000L,
        balance = balance,
        blockhash = Base58.decode("3K2oo9odsrvknS7gMzJ4eLBnjd3UG14yPuJc8DiviJqY"),
        requestId = ByteArray(16) { (it + 1).toByte() },
    )

    @Test
    fun solanaSendForm() = compose.snapshot("solana_send_form") {
        SolanaSendFormScreen(
            network = SOLANA_DEVNET,
            sender = sender,
            balance = balance,
            recipient = "",
            onRecipient = {},
            onScan = {},
            amount = "1.5",
            onAmount = {},
            onMax = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    @Test
    fun solanaSendReview() = compose.snapshot("solana_send_review") {
        SolanaSendReviewScreen(plan = plan, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun solanaSignRequest() = compose.snapshot("solana_send_request") {
        SolanaSignRequestScreen(parts = SolanaSend.requestParts(plan), notice = null, onScan = {}, onBack = {})
    }

    @Test
    fun solanaSendSent() = compose.snapshot("solana_send_sent") {
        SolanaSendSentScreen(
            network = SOLANA_DEVNET,
            signature = "B".repeat(88),
            outcome = SolanaOutcome.CONFIRMED,
            notice = null,
            onOpenExplorer = {},
            onDone = {},
        )
    }
}
