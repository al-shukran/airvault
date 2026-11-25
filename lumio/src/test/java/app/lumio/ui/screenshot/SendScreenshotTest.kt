package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import app.airvault.chains.evm.CALLDATA_HASH_LABEL
import app.airvault.chains.evm.calldataHash
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.components.groupAddress
import app.lumio.data.config.TransactionSettings
import app.lumio.evm.EvmChain
import app.lumio.evm.PreparedSend
import app.lumio.pair.PairFixtures
import app.lumio.tokens.Erc20
import app.lumio.tokens.Token
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendDeclinedPage
import app.lumio.ui.send.SendFeeSheet
import app.lumio.ui.send.SendLastChancePage
import app.lumio.ui.send.SendOnItsWayPage
import app.lumio.ui.send.SendOutcomePage
import app.lumio.ui.send.SendRecipientPage
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
import org.web3j.utils.Numeric

/**
 * The goldens of the send, one per page of the Crossing (Recipient, Amount, Review, Show to Lensly, Last Chance, On Its Way) and of the branches a send
 * can take: an invalid address, a Bitcoin address typed into an EVM send, an amount above the balance, a token asset, a first-time address, long names,
 * the fee sheet, Lensly Didn't Sign, the signature for another send, and a failed broadcast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val monad = EvmChain(10143L, "Monad Testnet", "MON", 18, true, "https://testnet.monadexplorer.com")
    private val from = AccountAddresses.addressAt(HdWallet(PairFixtures.seed).accountXpub(), 0)
    private val to = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val longName = "Savings for the house in the countryside and the boat"
    private val signPart = "ur:eth-sign-request/golden"
    private val accounts = listOf(SendChoice(title = "NothingLess", detail = "Your account", address = "0x" + "b2".repeat(20)))
    private val book = listOf(SendChoice(title = "Lena Park", detail = "Friends", address = "0x4b1e" + "c3".repeat(16)))

    /** 0.25 MON to a known recipient, from an account holding 12.5 MON, at a suggested fee cap of 161.9 gwei and a tip of 2 gwei. */
    private fun send(): PreparedSend = PreparedSend(
        chain = monad,
        index = 0,
        from = from,
        to = to,
        valueWei = BigInteger.valueOf(25).multiply(BigInteger.TEN.pow(16)),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = BigInteger.valueOf(161_900_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(2_000_000_000L),
        balanceWei = BigInteger.valueOf(125).multiply(BigInteger.TEN.pow(17)),
    )

    @Test
    fun ethereumSendRecipient() = compose.lumioSnapshot("ethereum_send_recipient") {
        SendRecipientPage(
            value = "",
            onValueChange = {},
            error = null,
            hint = null,
            canContinue = false,
            accounts = accounts,
            addressBook = book,
            onPick = {},
            onScan = {},
            onContinue = {},
            backLabel = "Account",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    /** The recipient is not an address: the field says why under it, and Continue stays disabled. */
    @Test
    fun ethereumSendRecipientInvalidAddress() = compose.lumioSnapshot("ethereum_send_recipient_invalid_address") {
        SendRecipientPage(
            value = "0x5aAeb6053F3E",
            onValueChange = {},
            error = "This isn't a Monad Testnet address",
            hint = null,
            canContinue = false,
            accounts = accounts,
            addressBook = book,
            onPick = {},
            onScan = {},
            onContinue = {},
            backLabel = "Account",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    /** A Bitcoin address in an EVM send: the field says it is not for this network, and the hint says where to send BTC from. */
    @Test
    fun ethereumSendRecipientBitcoinAddress() = compose.lumioSnapshot("ethereum_send_recipient_bitcoin_address") {
        SendRecipientPage(
            value = "bc1q9h7w3kx0g2lz8f5yt6v4r0d8qsm3p2a7kxw5e",
            onValueChange = {},
            error = "This isn't a Monad Testnet address",
            hint = "It looks like a Bitcoin address. To send BTC, start from a Bitcoin account.",
            canContinue = false,
            accounts = accounts,
            addressBook = book,
            onPick = {},
            onScan = {},
            onContinue = {},
            backLabel = "Account",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun ethereumSendAmount() = compose.lumioSnapshot("ethereum_send_amount") {
        SendAmountPage(
            symbol = "MON",
            toTitle = "FelixStone",
            toDetail = "Your account",
            toAddress = to,
            fromTitle = "PulsarNova",
            fromDetail = "12.5 MON available",
            asset = null,
            onAsset = null,
            amount = "0.25",
            onAmount = {},
            amountError = null,
            leaves = "Leaves 12.2466001 MON after the fee",
            onUseMax = {},
            networkName = monad.name,
            feeText = "Up to 0.0033999 MON",
            onFee = {},
            note = "Chosen for you: Monad Testnet, the network this account sends on.",
            canReview = true,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    /** The amount is more than the balance: the error takes the line under the amount, and Review is disabled. */
    @Test
    fun ethereumSendAmountInsufficientFunds() = compose.lumioSnapshot("ethereum_send_amount_insufficient_funds") {
        SendAmountPage(
            symbol = "MON",
            toTitle = "FelixStone",
            toDetail = "Your account",
            toAddress = to,
            fromTitle = "PulsarNova",
            fromDetail = "12.5 MON available",
            asset = null,
            onAsset = null,
            amount = "14",
            onAmount = {},
            amountError = "More than your available 12.5 MON",
            leaves = null,
            onUseMax = {},
            networkName = monad.name,
            feeText = null,
            onFee = {},
            note = null,
            canReview = false,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    /** A send with a token: the coin is picked on its own row, so the note that says what was chosen is not needed. */
    @Test
    fun ethereumTokenSendAmount() = compose.lumioSnapshot("ethereum_token_send_amount") {
        SendAmountPage(
            symbol = usdc.symbol,
            toTitle = "FelixStone",
            toDetail = "Your account",
            toAddress = to,
            fromTitle = "PulsarNova",
            fromDetail = "12.5 MON available",
            asset = usdc.symbol,
            onAsset = {},
            amount = "12.5",
            onAmount = {},
            amountError = null,
            leaves = null,
            onUseMax = {},
            networkName = monad.name,
            feeText = "Up to 0.0033999 MON",
            onFee = {},
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
    fun ethereumSendReview() = compose.lumioSnapshot("ethereum_send_review") {
        SendReviewPage(
            heading = "Send 0.25 MON from PulsarNova to FelixStone?",
            toTitle = "FelixStone",
            toDetail = "Your account",
            toAddress = to,
            validLine = "Address is valid on Monad Testnet",
            firstTime = null,
            rows = reviewRows(fromName = "PulsarNova"),
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

    /** A recipient nobody has named yet: the page says it is the first send there, and asks the owner to compare every group. */
    @Test
    fun ethereumSendReviewFirstTime() = compose.lumioSnapshot("ethereum_send_review_first_time") {
        SendReviewPage(
            heading = "Send 0.25 MON from PulsarNova to ${groupAddress(to)}?",
            toTitle = "Not in address book",
            toDetail = "",
            toAddress = to,
            validLine = "Address is valid on Monad Testnet",
            firstTime = "First time sending here. Compare every group with where you got it.",
            rows = reviewRows(fromName = "PulsarNova"),
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

    /** The recipient is in the Address Book under a name too long for one line, so the heading and the row both wrap it. */
    @Test
    fun ethereumSendReviewLongNames() = compose.lumioSnapshot("ethereum_send_review_long_names") {
        SendReviewPage(
            heading = "Send 0.25 MON from PulsarNova to $longName?",
            toTitle = longName,
            toDetail = "Address book",
            toAddress = to,
            validLine = "Address is valid on Monad Testnet",
            firstTime = null,
            rows = reviewRows(fromName = "PulsarNova"),
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

    /** A token send: its review ends with the hash of the transfer's calldata, the row Lensly shows for a call it cannot read. */
    @Test
    fun ethereumTokenSendReview() = compose.lumioSnapshot("ethereum_token_send_review") {
        val calldata = Numeric.hexStringToByteArray(Erc20.transfer(to, BigInteger.valueOf(12_500_000)))
        SendReviewPage(
            heading = "Send 12.5 USDC from PulsarNova to FelixStone?",
            toTitle = "FelixStone",
            toDetail = "Your account",
            toAddress = to,
            validLine = "Address is valid on Monad Testnet",
            firstTime = null,
            rows = listOf(
                SummaryRow("Amount", "12.5 USDC"),
                SummaryRow("Max Fee", "0.0033999 MON"),
                SummaryRow("Network", "Monad Testnet · ID 10143"),
                SummaryRow("From", "PulsarNova"),
                SummaryRow(CALLDATA_HASH_LABEL, calldataHash(calldata), mono = true),
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
    fun ethereumSendShow() = compose.lumioSnapshot("ethereum_send_show") {
        SendShowPage(
            parts = listOf(signPart),
            onNext = {},
            onCancel = {},
            backLabel = "Review",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    /** The fee sheet on the Normal stop, with the fee cap and the tip in gwei under Advanced. */
    @Test
    fun ethereumSendFeeSheet() = compose.lumioSnapshot("ethereum_send_fee_sheet") {
        SendFeeSheet(
            visible = true,
            suggested = send(),
            current = send(),
            timing = "Arrives in about 1 second on Monad Testnet",
            onApply = {},
            onClose = {},
        )
    }

    @Test
    fun ethereumSendLastChance() = compose.lumioSnapshot("ethereum_send_last_chance") {
        SendLastChancePage(
            recipient = "FelixStone",
            amount = "0.25 MON",
            remaining = 42,
            total = TransactionSettings.DEFAULT_LAST_CHANCE_SECONDS,
            sending = false,
            onSendNow = {},
            onStop = {},
            backLabel = "Review",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun ethereumSendOnItsWay() = compose.lumioSnapshot("ethereum_send_on_its_way") {
        SendOnItsWayPage(
            headline = "Your MON Is on Its Way",
            summary = "0.25 MON to FelixStone",
            network = monad.name,
            timing = "Usually about 1 second on Monad Testnet",
            recipient = "FelixStone",
            signedAtMs = GOLDEN_CLOCK_MS,
            sentAtMs = GOLDEN_CLOCK_MS,
            arrived = false,
            explorerLabel = "View on Explorer",
            onExplorer = {},
            onDone = {},
            backLabel = "",
            onBack = {},
            onLock = {},
        )
    }

    /** The network refused the transaction and the same bytes can be sent again: Try Again is the one action. */
    @Test
    fun ethereumSendFailed() = compose.lumioSnapshot("ethereum_send_failed") {
        SendOutcomePage(
            heading = "Nothing Left Your Account",
            body = "The transaction could not be sent. Check the balance, then try again.",
            notes = listOf(
                "What Happened" to "The transaction could not be sent. Check the balance, then try again.",
                "What You Can Do" to "Try again sends the same signed transaction. Nothing was sent.",
            ),
            detail = "replacement transaction underpriced",
            primary = "Try Again",
            onPrimary = {},
            secondary = "Back to PulsarNova",
            onSecondary = {},
            backLabel = "",
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun ethereumSendLenslyDidntSign() = compose.lumioSnapshot("ethereum_send_lensly_didnt_sign") {
        SendDeclinedPage(amount = "0.25 MON", fromName = "PulsarNova", backLabel = "", onBack = {}, onLock = {})
    }

    /** Lensly's reply answers another send, so the page says so and never broadcasts it. Scan Again is the one way on. */
    @Test
    fun ethereumSendSignatureMismatch() = compose.lumioSnapshot("ethereum_send_signature_mismatch") {
        SendOutcomePage(
            heading = "This Signature Is for a Different Send",
            body = "Lensly's reply doesn't match this send. Nothing was sent, and your 0.25 MON is still in PulsarNova.",
            notes = listOf(
                "What Happened" to "Lensly's reply doesn't match this send. Lumio never sends a signature that doesn't match.",
                "What You Can Do" to "On Lensly, check that it shows 0.25 MON to FelixStone, then scan its reply again.",
            ),
            detail = null,
            primary = "Scan Again",
            onPrimary = {},
            secondary = "Cancel Send",
            onSecondary = {},
            backLabel = "",
            onBack = {},
            onLock = {},
        )
    }

    private fun reviewRows(fromName: String): List<SummaryRow> = listOf(
        SummaryRow("Amount", "0.25 MON"),
        SummaryRow("Max Fee", "0.0033999 MON"),
        SummaryRow("Total at Most", "0.2533999 MON"),
        SummaryRow("Network", "Monad Testnet · ID 10143"),
        SummaryRow("From", fromName),
    )
}
