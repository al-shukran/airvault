package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import app.lensly.app.AddressBookEntry
import app.lumio.evm.EvmChain
import app.lumio.evm.PreparedSend
import app.lumio.evm.SendAsset
import app.lumio.tokens.Token
import app.lumio.ui.send.SendFormScreen
import app.lumio.ui.send.SendReviewScreen
import app.lumio.ui.send.SignRequestScreen
import app.lumio.ui.send.evmSummary
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Ethereum send, one per step (Details, Review, Sign, Send) and the edge states a send can be in: an invalid address, an
 * insufficient balance, an empty token balance and long names (Phase 2.8 WP6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
    private val from = "0x" + "a1".repeat(20)
    private val to = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val nativeAssets = listOf(SendAsset(ethereum, null, null))
    private val tokenAssets = listOf(SendAsset(ethereum, null, null), SendAsset(ethereum, usdc, BigInteger.valueOf(1_250_500_000L)))
    private val longName = "Savings for the house in the countryside and the boat"
    private val txId = "0x" + "ab".repeat(32)
    private val signPart = "ur:eth-sign-request/golden"

    private fun send(token: Token? = null): PreparedSend = PreparedSend(
        chain = ethereum,
        index = 0,
        from = from,
        to = to,
        valueWei = if (token == null) BigInteger.TEN.pow(16) else BigInteger.valueOf(12_500_000L),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = BigInteger.TEN.pow(18),
        token = token,
        tokenBalance = if (token == null) null else BigInteger.valueOf(1_250_500_000L),
    )

    @Test
    fun ethereumSendReview() = compose.lumioSnapshot("ethereum_send_review") {
        SendReviewScreen(send = send(), suggested = send(), notice = null, onSave = {}, onConfirm = {}, onBack = {})
    }

    @Test
    fun ethereumTokenSendReview() = compose.lumioSnapshot("ethereum_token_send_review") {
        SendReviewScreen(send = send(usdc), suggested = send(usdc), notice = null, onSave = {}, onConfirm = {}, onBack = {})
    }

    /** The recipient is in the Address Book under a name too long for one line, so the sentence and the address show it. */
    @Test
    fun ethereumSendReviewLongNames() = compose.lumioSnapshot("ethereum_send_review_long_names") {
        SendReviewScreen(
            send = send(),
            suggested = send(),
            notice = null,
            onSave = {},
            onConfirm = {},
            onBack = {},
            addressBook = listOf(AddressBookEntry(id = "1", address = to, name = longName, label = null)),
        )
    }

    @Test
    fun ethereumSendSign() = compose.lumioSnapshot("ethereum_send_sign") {
        SignRequestScreen(send = send(), parts = listOf(signPart), notice = null, onScan = {}, onBack = {})
    }

    @Test
    fun ethereumSendSent() = compose.lumioSnapshot("ethereum_send_sent") {
        SubmitFixture(
            machine = sentSubmit(txId),
            rows = evmSummary(send()),
            explorerUrl = { "https://etherscan.io/tx/$it" },
        )
    }

    /** The recipient is not an address, so the field says why under it and Review does not go through. */
    @Test
    fun ethereumSendFormInvalidAddress() = compose.lumioSnapshot("ethereum_send_form_invalid_address") {
        SendFormScreen(
            fromLabel = "Address 1",
            fromAddress = from,
            chain = ethereum,
            assets = nativeAssets,
            selectedAsset = nativeAssets.first(),
            onSelectAsset = {},
            recipient = "0x5aAeb6053F3E",
            onRecipient = {},
            onScan = {},
            amount = "0.01",
            onAmount = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    /** The amount is more than the token balance, so the send says so and stays on the Details step. */
    @Test
    fun ethereumSendFormInsufficientFunds() = compose.lumioSnapshot("ethereum_send_form_insufficient_funds") {
        SendFormScreen(
            fromLabel = "Address 1",
            fromAddress = from,
            chain = ethereum,
            assets = tokenAssets,
            selectedAsset = tokenAssets[1],
            onSelectAsset = {},
            recipient = to,
            onRecipient = {},
            onScan = {},
            amount = "2000",
            onAmount = {},
            busy = false,
            notice = "This account does not hold enough USDC for this amount.",
            onReview = {},
            onBack = {},
        )
    }

    /** The token is held by no balance on this address, so the picker says so under it. */
    @Test
    fun ethereumSendFormEmptyBalance() = compose.lumioSnapshot("ethereum_send_form_empty_balance") {
        SendFormScreen(
            fromLabel = "Address 1",
            fromAddress = from,
            chain = ethereum,
            assets = listOf(SendAsset(ethereum, null, null), SendAsset(ethereum, usdc, null)),
            selectedAsset = SendAsset(ethereum, usdc, null),
            onSelectAsset = {},
            recipient = to,
            onRecipient = {},
            onScan = {},
            amount = "",
            onAmount = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    /** The account has a name too long for one line, and the Details step wraps it rather than cut it. */
    @Test
    fun ethereumSendFormLongNames() = compose.lumioSnapshot("ethereum_send_form_long_names") {
        SendFormScreen(
            fromLabel = longName,
            fromAddress = from,
            chain = ethereum,
            assets = tokenAssets,
            selectedAsset = tokenAssets[1],
            onSelectAsset = {},
            recipient = to,
            onRecipient = {},
            onScan = {},
            amount = "12.5",
            onAmount = {},
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }
}
