package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.AccountId
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressKeyOf
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.networkId
import app.lumio.chains.ChainHolding
import app.lumio.chains.ChainHoldings
import app.lumio.data.Clock
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lumio.portfolio.ChainFamily
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.TxDetail
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.asset.HoldingsPage
import app.lumio.ui.asset.WalletHolding
import app.lumio.ui.asset.accountDetail
import app.lumio.ui.asset.amountText
import app.lumio.ui.asset.chainHoldings
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.receive.ReceiveFlowSheet
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.send.SendChoice
import app.lumio.ui.send.SendRecipientPage
import app.lumio.ui.settings.AddressBookScreen
import app.lumio.ui.transactions.ActivityList
import app.lumio.ui.transactions.TransactionDetailScreen
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.WalletScreen
import app.lumio.wallets.AccountCoin
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design-review renders for #1692 (addresses in groups of four in Lumio): every screen that shows a short address, with realistic addresses, in the day
 * and night themes, plus a small phone at a large font. The same file runs on master for the "before" renders. Not for merge: drop it with its PNGs
 * once the design is approved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressReviewScreenshotTest {

    @get:org.junit.Rule
    val compose = createComposeRule()

    @Test fun activityDay() = compose.lumioSnapshot("r1692_activity_day") { Activity(review1692Activity()) }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun activityNight() = compose.lumioSnapshot("r1692_activity_night") { Activity(review1692Activity()) }

    /** Option B of the open decision: the list leaves out an address that has no name, and the transaction page shows it. */
    @Test fun activityNoAddressDay() = compose.lumioSnapshot("r1692_activity_b_day") { Activity(review1692Activity(unnamedAddresses = false)) }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun activityNoAddressNight() = compose.lumioSnapshot("r1692_activity_b_night") { Activity(review1692Activity(unnamedAddresses = false)) }

    @Test fun receiveDay() = compose.lumioSnapshot("r1692_receive_day", beforeCapture = { pick("USD Coin", "Base") }) { Receive() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun receiveNight() = compose.lumioSnapshot("r1692_receive_night", beforeCapture = { pick("USD Coin", "Base") }) { Receive() }

    @Test fun walletDay() = compose.lumioSnapshot("r1692_wallet_day") { Wallet() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun walletNight() = compose.lumioSnapshot("r1692_wallet_night") { Wallet() }

    @Test fun holdingsDay() = compose.lumioSnapshot("r1692_holdings_day") { Holdings() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun holdingsNight() = compose.lumioSnapshot("r1692_holdings_night") { Holdings() }

    @Test fun pickerDay() = compose.lumioSnapshot("r1692_picker_day") { Picker() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun pickerNight() = compose.lumioSnapshot("r1692_picker_night") { Picker() }

    @Test fun addressBookDay() = compose.lumioSnapshot("r1692_addressbook_day") { Book() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun addressBookNight() = compose.lumioSnapshot("r1692_addressbook_night") { Book() }

    @Test fun txDetailDay() = compose.lumioSnapshot("r1692_txdetail_day") { Detail() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun txDetailNight() = compose.lumioSnapshot("r1692_txdetail_night") { Detail() }

    @Test fun recipientDay() = compose.lumioSnapshot("r1692_recipient_day") { Recipient() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun recipientNight() = compose.lumioSnapshot("r1692_recipient_night") { Recipient() }

    @Test fun amountDay() = compose.lumioSnapshot("r1692_amount_day") { Amount() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun amountNight() = compose.lumioSnapshot("r1692_amount_night") { Amount() }

    @Test fun accountDay() = compose.lumioSnapshot("r1692_account_day") { Account() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun accountNight() = compose.lumioSnapshot("r1692_account_night") { Account() }

    @Test fun solanaDay() = compose.lumioSnapshot("r1692_solana_day") { Solana() }

    @Test @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun solanaNight() = compose.lumioSnapshot("r1692_solana_night") { Solana() }

    // A small phone (360 dp) at the Large font (1.3x), and the Account page at the largest (2.0x): nothing may clip, and an address breaks only at
    // its ellipsis.
    @Test fun activitySmall() = compose.lumioSnapshot("r1692_activity_small", frame = SMALL, fontScale = LARGE) { Activity(review1692Activity()) }

    @Test fun walletSmall() = compose.lumioSnapshot("r1692_wallet_small", frame = SMALL, fontScale = LARGE) { Wallet() }

    @Test fun receiveSmall() = compose.lumioSnapshot("r1692_receive_small", frame = SMALL, fontScale = LARGE, beforeCapture = { pick("USD Coin", "Base") }) { Receive() }

    @Test fun addressBookSmall() = compose.lumioSnapshot("r1692_addressbook_small", frame = SMALL, fontScale = LARGE) { Book() }

    @Test fun txDetailSmall() = compose.lumioSnapshot("r1692_txdetail_small", frame = SMALL, fontScale = LARGE) { Detail() }

    @Test fun accountLargest() = compose.lumioSnapshot("r1692_account_font2", frame = SMALL, fontScale = 2f) { Account() }
}

private val SMALL = DpSize(360.dp, 800.dp)
private const val LARGE = 1.3f

// Addresses: EIP-55 and BIP-173 test vectors and made-up keys, never a real person's.
private const val TRADING = "0x1593D398aF7c02E1b9C4d6E8f0A27b913eA25343"
private const val SAVINGS = "0x9f3c0A12d4E5b6C7a8F9e0D1c2B3a4F52c3d41d0"
private const val COLD = "0x7E21c04bA9d3F6e58C1b2D7a4E9f0c3B5ac33c5E"
private const val LENA = "0x4b1eC3a907dE52F1a6B8c9D0e1F2a3B4c5D6e7F8"
private const val EXCHANGE = "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb"
private const val UNNAMED_1 = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val UNNAMED_2 = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
private const val UNNAMED_3 = "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB"
private const val BTC_OWN = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
private const val BTC_OTHER = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
private const val SOL_OWN = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
private const val SOL_OTHER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
private const val SOL_MINT = "4kUf8YqW3nPZx2sLm7RtV9cB6dHj1eKqA5gNwX8yZpLm"
private const val ALGO_OWN = "VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA"

private val NOON_MS = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS
private val clock = Clock { NOON_MS }

private val ETHEREUM = NetworkId(Networks.evm(1), testnet = false)
private val ARBITRUM = NetworkId(Networks.evm(42161), testnet = false)
private val BASE = NetworkId(Networks.evm(8453), testnet = false)
private val BITCOIN = BtcNetwork.MAINNET.networkId()
private val ETHER = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18)
private val USDC = TxAsset(contract = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", symbol = "USDC", name = "USD Coin", decimals = 6)

private fun tx(n: Int, network: NetworkId, asset: TxAsset, amount: String, direction: TxDirection, party: String?, atMs: Long, pending: Boolean = false, call: Boolean = false) = LumioTx(
    id = "r1692-$n",
    network = network,
    account = if (network == BITCOIN) BTC_OWN else if (network == SOLANA_MAINNET) SOL_OWN else TRADING,
    hash = "0x" + "%064x".format(0x5e1d00 + n),
    timestampMs = atMs,
    asset = asset,
    amount = BigDecimal(amount).movePointRight(asset.decimals).toBigIntegerExact(),
    direction = direction,
    fee = BigInteger.valueOf(42_000),
    counterparty = party,
    status = if (pending) TxStatus.PENDING else TxStatus.CONFIRMED,
    confirmations = if (pending) 0L else 120L,
    inputData = if (call) "0xa9059cbb" else null,
    memo = null,
)

/**
 * The review's activity: a receive from a name in the address book, a pending contract call, receives and sends with addresses that have no name on
 * Ethereum, Bitcoin and Solana, and a move to one of the wallet's own accounts. With [unnamedAddresses] off, an address with no name is left out.
 */
internal fun review1692Activity(unnamedAddresses: Boolean = true): List<TxFeedItem> {
    fun p(address: String) = address.takeIf { unnamedAddresses }
    val sol = TxAsset(contract = null, symbol = "SOL", name = "Solana", decimals = 9)
    val btc = TxAsset(contract = null, symbol = "BTC", name = "Bitcoin", decimals = 8)
    return listOf(
        TxFeedItem(tx(1, ARBITRUM, ETHER, "1", TxDirection.IN, EXCHANGE, NOON_MS), usd = 4285.7, chain = "Arbitrum One"),
        TxFeedItem(tx(2, ETHEREUM, ETHER, "0.25", TxDirection.OUT, p(UNNAMED_1), NOON_MS - HOUR_MS, pending = true, call = true), usd = 1071.43, chain = "Ethereum"),
        TxFeedItem(tx(3, BITCOIN, btc, "0.0042", TxDirection.IN, p(BTC_OTHER), NOON_MS - 2 * HOUR_MS), usd = 455.36, chain = "Bitcoin"),
        TxFeedItem(tx(4, BASE, USDC, "5000", TxDirection.OUT, p(UNNAMED_2), NOON_MS - DAY_MS), usd = 5000.0, chain = "Base"),
        TxFeedItem(tx(5, SOLANA_MAINNET, sol, "2", TxDirection.IN, p(SOL_OTHER), NOON_MS - DAY_MS - 3 * HOUR_MS), usd = 412.0, chain = "Solana"),
        TxFeedItem(tx(6, ETHEREUM, ETHER, "0.5", TxDirection.OUT, SAVINGS, NOON_MS - 2 * DAY_MS), usd = 2142.85, chain = "Ethereum"),
        TxFeedItem(tx(7, ETHEREUM, ETHER, "0.12", TxDirection.IN, p(UNNAMED_3), NOON_MS - 2 * DAY_MS - HOUR_MS), usd = 514.28, chain = "Ethereum"),
    )
}

/** The names Lumio knows: one address-book name and one of the wallet's own accounts, by address key. */
private val NAMES = mapOf(addressKeyOf(EXCHANGE) to "Exchange Deposit", addressKeyOf(SAVINGS) to "Savings")

@Composable
private fun Activity(items: List<TxFeedItem>) {
    ActivityList(
        items = items,
        filter = ActivityFilter(),
        onFilter = {},
        families = listOf(ChainFamily.EVM, ChainFamily.BTC),
        names = NAMES,
        spamHidden = 0,
        showingSpam = false,
        onShowSpam = {},
        showBalance = true,
        zone = ZoneOffset.UTC,
        nowMs = NOON_MS,
        onOpen = {},
    )
}

private fun ComposeContentTestRule.pick(vararg rows: String) {
    mainClock.autoAdvance = true
    rows.forEach { row ->
        onNodeWithText(row).performTouchInput { click() }
        waitForIdle()
    }
}

@Composable
private fun Receive() {
    val accounts = listOf(
        ReceiveAccount(name = "Trading", label = "Daily", group = "DeFi", address = TRADING),
        ReceiveAccount(name = "Savings", label = "Long term", group = "Core", address = SAVINGS),
        ReceiveAccount(name = "Cold Storage", label = null, group = null, address = COLD),
    )
    val networks = listOf(
        ReceiveNetwork("Ethereum Layer 1", AssetIconSpec.Chain(null), accounts),
        ReceiveNetwork("Base L2 on Ethereum", AssetIconSpec.Letter("B"), accounts, "Only accounts that can hold USDC on Base are listed.", label = "Base"),
        ReceiveNetwork("Arbitrum L2 on Ethereum", AssetIconSpec.Letter("A"), accounts),
    )
    val usdc = ReceiveCoin(symbol = "USDC", name = "USD Coin", subtitle = "USDC · on 3 networks", held = true, icon = AssetIconSpec.Letter("U"), networks = networks)
    val eth = ReceiveCoin(symbol = "ETH", name = "Ethereum", subtitle = "ETH · on 3 networks you use", held = true, icon = AssetIconSpec.Chain(null), networks = networks)
    ReceiveFlowSheet(visible = true, opening = 0, start = ReceiveAnswers(), assets = listOf(eth, usdc), onClose = {})
}

private fun account(network: NetworkId, chain: String, address: String, index: Int, coin: String, amount: String, name: String? = null, script: String? = null) =
    WalletAccount(
        walletId = "lumio",
        id = AccountId.of(network.chain, address),
        index = index,
        networkName = chain,
        coin = AccountCoin(coin, chain, network),
        balance = NativeAsset("$coin-$index-$chain", coin, chain, network, BigDecimal(amount)),
        script = script,
        name = name,
    )

private val ethTrading = account(ETHEREUM, "Ethereum", TRADING, 0, "ETH", "4.2", name = "Trading")
private val ethSavings = account(ETHEREUM, "Ethereum", SAVINGS, 1, "ETH", "12", name = "Savings")
private val ethCold = account(ETHEREUM, "Ethereum", COLD, 2, "ETH", "4.6", name = "Cold Storage")
private val btcMain = account(BITCOIN, "Bitcoin", BTC_OWN, 0, "BTC", "0.2104", script = "Native SegWit")
private val solMain = account(SOLANA_MAINNET, "Solana", SOL_OWN, 0, "SOL", "75", name = "Phone")
private val algoMain = account(NetworkId(Networks.ALGO_MAINNET, testnet = false), "Algorand", ALGO_OWN, 0, "ALGO", "52000")

private val USD_PER = mapOf("ETH" to 4285.7, "BTC" to 108_400.0, "SOL" to 206.0, "ALGO" to 0.31)

@Composable
private fun Wallet() {
    val accounts = listOf(btcMain, ethTrading, ethSavings, ethCold, solMain, algoMain)
    WalletScreen(
        mode = NetworkMode.MAINNET,
        accounts = accounts,
        tokenCounts = mapOf(ethTrading to 2),
        totalUsd = 125_603.0,
        usdOf = { a -> a.balance?.let { b -> USD_PER[b.symbol]?.let { b.balance.toDouble() * it } } },
        showTotal = true,
        showBalances = true,
        updatedAtMs = NOON_MS - 3 * 60_000L,
        notice = null,
        pairedAtMs = NOON_MS - 14 * DAY_MS,
        zone = ZoneOffset.UTC,
        onAccount = {},
        onRefresh = {},
        clock = clock,
    )
}

private fun holdings() = chainHoldings(
    listOf(
        WalletHolding(ethSavings, ETHEREUM, "Ethereum", BigDecimal("12")),
        WalletHolding(ethTrading, ETHEREUM, "Ethereum", BigDecimal("4.2")),
        WalletHolding(ethTrading.copy(id = AccountId.of(Networks.evm(42161), TRADING)), ARBITRUM, "Arbitrum One", BigDecimal("5")),
        WalletHolding(ethCold.copy(id = AccountId.of(Networks.evm(8453), COLD)), BASE, "Base", BigDecimal("1.03")),
    ),
    walletName = "Lumio",
)

@Composable
private fun Holdings() {
    HoldingsPage(title = "Your ETH", symbol = "ETH", chains = holdings(), showBalance = true, onAccount = {}, onBack = {}, onLock = {})
}

@Composable
private fun Picker() {
    Holdings()
    ClearAirSheet(visible = true, onClose = {}) {
        ClearAirChoiceSheet(
            title = "Send ETH from",
            groups = listOf(ChoiceGroup(null, listOf(ethSavings to "12", ethTrading to "4.2", ethCold to "4.6").map { (a, amount) -> pickerRow(a, amount) })),
        )
    }
}

/** One account of the Send-from picker, as the Asset page builds it. */
private fun pickerRow(account: WalletAccount, amount: String) = ChoiceRow(
    title = account.name ?: account.networkName,
    onPick = {},
    detail = accountDetail("Lumio", account),
    trailing = amountText(BigDecimal(amount), "ETH"),
    address = account.address,
)

@Composable
private fun Book() {
    AddressBookScreen(
        entries = listOf(
            AddressBookEntry(id = "1", address = LENA, name = "Lena Park", label = "Friends"),
            AddressBookEntry(id = "2", address = EXCHANGE, name = "Exchange Deposit", label = "Exchange"),
            AddressBookEntry(id = "3", address = UNNAMED_2, name = "Rent", label = null),
            AddressBookEntry(id = "4", address = BTC_OTHER, name = "Hardware Wallet", label = "Cold"),
            AddressBookEntry(id = "5", address = SOL_OTHER, name = "Mia", label = "Family"),
        ),
        offline = null,
        onEdit = {},
        onBack = {},
        onLock = {},
    )
}

@Composable
private fun Detail() {
    TransactionDetailScreen(
        detail = TxDetail(
            tx = tx(8, ETHEREUM, ETHER, "1.5", TxDirection.IN, UNNAMED_1, NOON_MS - 3 * DAY_MS),
            usd = 6428.55,
            comment = null,
            chain = "Ethereum",
            feeText = "0.00042 ETH",
            explorerUrl = "https://etherscan.io/tx/0x1",
            flags = emptyList(),
        ),
        accountNames = emptyMap(),
        book = emptyList(),
        backLabel = "Activity",
        showBalance = true,
        onAddress = {},
        onBack = {},
        onExplorer = {},
        onLock = {},
        zone = ZoneOffset.UTC,
    )
}

@Composable
private fun Recipient() {
    SendRecipientPage(
        value = "",
        onValueChange = {},
        error = null,
        hint = null,
        canContinue = false,
        accounts = listOf(SendChoice("Savings", "Your account", SAVINGS), SendChoice("Cold Storage", "Your account", COLD)),
        addressBook = listOf(SendChoice("Lena Park", "Friends", LENA), SendChoice("Exchange Deposit", "Exchange", EXCHANGE)),
        onPick = {},
        onScan = {},
        onContinue = {},
        backLabel = "Account",
        offline = null,
        onBack = {},
        onLock = {},
    )
}

@Composable
private fun Amount() {
    SendAmountPage(
        symbol = "ETH",
        toTitle = "Lena Park",
        toDetail = "Friends",
        toAddress = LENA,
        fromTitle = "Trading",
        fromDetail = "4.2 ETH available",
        asset = null,
        onAsset = null,
        amount = "0.25",
        onAmount = {},
        amountError = null,
        leaves = "Leaves 3.9496 ETH after the fee",
        onUseMax = {},
        networkName = "Ethereum",
        feeText = "Up to 0.0004 ETH",
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

private fun rowsOf(account: WalletAccount, holdings: List<ChainHolding> = emptyList()): List<AssetRowModel> =
    AssetRows.of(account, emptyList(), holdings, emptyMap(), network = { null }, token = { _, _ -> null })

@Composable
private fun Account() {
    AccountScreen(
        walletName = "Lumio",
        account = ethTrading.copy(label = "Daily", group = "Group DeFi"),
        showBalance = true,
        assets = rowsOf(ethTrading),
        activity = review1692Activity().filter { it.tx.network == ETHEREUM },
        updatedAtMs = NOON_MS - 3 * 60_000L,
        onSend = {},
        onReceive = {},
        onOpenTransaction = {},
        onRefresh = {},
        clock = clock,
        zone = ZoneOffset.UTC,
    )
}

@Composable
private fun Solana() {
    val unknown = ChainHolding(ChainHoldings.shortened(SOL_MINT), "Solana", BigInteger("2500000000"), 6, AssetId.Spl(SOLANA_MAINNET, SOL_MINT))
    AccountScreen(
        walletName = "Lumio",
        account = solMain,
        showBalance = true,
        assets = rowsOf(solMain, holdings = listOf(unknown)),
        onSend = {},
        onReceive = {},
        onRefresh = {},
    )
}
