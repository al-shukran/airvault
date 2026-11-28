package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.airvault.core.model.AccountId
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.networkId
import app.lumio.chains.ChainHoldings
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lensly.app.AddressBookEntry
import app.lumio.receive.ReceiveAccount
import app.lumio.receive.ReceiveAnswers
import app.lumio.receive.ReceiveCoin
import app.lumio.receive.ReceiveNetwork
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.TxDetail
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.asset.AccountHolding
import app.lumio.ui.asset.ChainHolding
import app.lumio.ui.asset.HoldingsPage
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
import app.lumio.ui.shell.LumioPageScreen
import app.lumio.ui.transactions.AccountFace
import app.lumio.ui.transactions.ActivityList
import app.lumio.ui.transactions.TransactionDetailScreen
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.WalletScreen
import app.lumio.wallets.AccountCoin
import app.lumio.wallets.WalletAccount
import app.lumio.portfolio.ChainFamily
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens that write an address in groups of four (#1692) on a small phone with large text: 360 dp wide at font scale 1.3, in the day palette, with
 * the longest names Lumio allows (a wallet and an account of 32 characters, a contact of 20). Each is the approved design's 360 dp, 130% frame, and each
 * must show the address whole, with no clipped text and no break inside a group. Recorded with `:lumio:recordRoborazziDebug` (`record-goldens.yml`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressGroupsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val frame = DpSize(360.dp, 800.dp)

    private fun snap(name: String, content: @Composable () -> Unit) = compose.lumioSnapshot("address_groups_$name", frame = frame, fontScale = 1.3f, content = content)

    private val walletName = "Family Savings and Travel Wallet"
    private val accountName = "Long Term Savings for the Family"
    private val contactName = "Grandmother Margaret"

    private val own = "0xF278cF59F5d0E2A6d0C3c2f4A9d2a3D9f25C1cdb"
    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private val margaret = "0x4b1e9d0c7a3f5e2186b4d9a0c3e7f1250000a07c"

    private val ethereum = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(Networks.evm(1), own),
        index = 0,
        networkName = "Ethereum",
        coin = AccountCoin("ETH", "Ethereum", NetworkId(Networks.evm(1), testnet = false)),
        balance = NativeAsset("EVM-1", "ETH", "Ethereum", NetworkId(Networks.evm(1), testnet = false), BigDecimal("12.5")),
        name = accountName,
    )
    private val bitcoin = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(Networks.BTC_MAINNET, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"),
        index = 0,
        networkName = "Bitcoin",
        coin = AccountCoin("BTC", "Bitcoin", BtcNetwork.MAINNET.networkId()),
        balance = NativeAsset("BTC", "BTC", "Bitcoin", BtcNetwork.MAINNET.networkId(), BigDecimal("0.0012")),
        script = "Native SegWit",
        name = accountName,
    )
    private val solana = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(Networks.SOL_DEVNET, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"),
        index = 0,
        networkName = "Solana Devnet",
        coin = AccountCoin("SOL", "Solana Devnet", SOLANA_DEVNET),
        balance = NativeAsset("SOL-devnet", "SOL", "Solana Devnet", SOLANA_DEVNET, BigDecimal("0.4")),
        name = accountName,
    )
    private val algorand = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(Networks.ALGO_TESTNET, "VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA"),
        index = 0,
        networkName = "Algorand Testnet",
        coin = null,
        balance = null,
        name = accountName,
    )

    private fun tx(id: String, direction: TxDirection, counterparty: String, minutesAgo: Long, symbol: String = "ETH", amount: String = "1000000000000000000") = TxFeedItem(
        tx = LumioTx(
            id = id,
            network = NetworkId(Networks.evm(1), testnet = false),
            account = own,
            hash = "0x" + "32".repeat(32),
            timestampMs = NOW_MS - minutesAgo * 60_000L,
            asset = if (symbol == "ETH") TxAsset(null, "ETH", "Ether", 18) else TxAsset("0x" + "ab".repeat(20), symbol, "USD Coin", 6),
            amount = BigInteger(amount),
            direction = direction,
            fee = null,
            counterparty = counterparty,
            status = TxStatus.CONFIRMED,
            confirmations = 120L,
            inputData = null,
            memo = null,
        ),
        usd = 4285.7,
        chain = "Ethereum",
    )

    @Test
    fun activity() = snap("activity") {
        ActivityList(
            items = listOf(
                tx("a", TxDirection.IN, "0x" + "9f".repeat(20), 30),
                tx("b", TxDirection.OUT, margaret, 24 * 60 + 30, amount = "500000000000000000"),
                tx("c", TxDirection.IN, vitalik, 3 * 24 * 60, symbol = "USDC", amount = "5000000000"),
            ),
            filter = ActivityFilter(),
            onFilter = {},
            families = listOf(ChainFamily.EVM),
            names = mapOf("0x" + "9f".repeat(20) to "Exchange Deposit", margaret to contactName),
            spamHidden = 0,
            showingSpam = false,
            onShowSpam = {},
            showBalance = true,
            zone = ZoneOffset.UTC,
            nowMs = NOW_MS,
            onOpen = {},
        )
    }

    private fun detail(counterparty: String = margaret) = TxDetail(
        tx = tx("d", TxDirection.OUT, counterparty, 24 * 60, amount = "500000000000000000").tx,
        usd = 2142.85,
        comment = null,
        chain = "Ethereum",
        feeText = "0.0017199 ETH",
        explorerUrl = null,
        flags = emptyList(),
    )

    private val book = listOf(AddressBookEntry(id = "entry-1", address = margaret, name = contactName, label = null, group = "Friends"))

    @Test
    fun transactionDetail() = snap("tx_detail") {
        TransactionDetailScreen(
            detail = detail(),
            accountNames = mapOf(own.lowercase() to "PulsarNova"),
            book = book,
            backLabel = "Activity",
            showBalance = true,
            onAddress = {},
            onExplorer = {},
            onBack = {},
            onLock = {},
            zone = ZoneOffset.UTC,
        )
    }

    /** The own account has no name: the wallet's "Account 1" and "E1" stand in its place. */
    @Test
    fun transactionDetailUnnamedAccount() = snap("tx_detail_unnamed") {
        TransactionDetailScreen(
            detail = detail(),
            accountNames = emptyMap(),
            book = book,
            backLabel = "Activity",
            showBalance = true,
            onAddress = {},
            onExplorer = {},
            onBack = {},
            onLock = {},
            zone = ZoneOffset.UTC,
            unnamedAccount = AccountFace(title = "Account 1", monogram = "E1"),
        )
    }

    private val receiveNetwork = ReceiveNetwork(
        name = "Ethereum Layer 1",
        icon = AssetIconSpec.Chain(null),
        accounts = listOf(
            ReceiveAccount(name = accountName, label = "Trading", group = "DeFi", address = own),
            ReceiveAccount(name = "NothingLess 1", label = null, group = null, address = vitalik),
        ),
    )

    @Test
    fun receiveAccountStep() = snap("receive_account") {
        val coin = ReceiveCoin("ETH", "Ethereum", "", true, AssetIconSpec.Chain(null), listOf(receiveNetwork))
        ReceiveFlowSheet(visible = true, opening = 0, start = ReceiveAnswers(coin = coin, network = receiveNetwork), assets = listOf(coin), onClose = {})
    }

    /** The Receive To picker of an Asset page: the wallet's name wraps, the address is a line of its own. */
    @Test
    fun accountPicker() = snap("picker") {
        ClearAirSheet(visible = true, onClose = {}) {
            ClearAirChoiceSheet(
                title = "Receive ETH to",
                groups = listOf(
                    ChoiceGroup(
                        null,
                        listOf(
                            ChoiceRow(title = accountName, onPick = {}, detail = "$walletName #0", address = own, trailing = "4 ETH"),
                            ChoiceRow(title = "FelixStone", onPick = {}, detail = "$walletName #1", address = vitalik, trailing = "1 ETH"),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun assetHoldings() = snap("holdings") {
        fun holding(name: String, address: String, amount: String) = AccountHolding(
            walletId = "wallet",
            accountId = AccountId.of(Networks.evm(1), address),
            chain = "Ethereum",
            name = name,
            detail = "$walletName #0",
            address = address,
            amount = BigDecimal(amount),
        )
        HoldingsPage(
            title = "Your ETH",
            symbol = "ETH",
            chains = listOf(
                ChainHolding("Ethereum", NetworkId(Networks.evm(1), testnet = false), BigDecimal("6"), listOf(holding(accountName, own, "5"), holding("FelixStone", vitalik, "1"))),
            ),
            showBalance = true,
            onAccount = {},
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun wallet() = snap("wallet") {
        LumioPageScreen(title = walletName, backLabel = "Wallets", offline = null, onBack = {}, onLock = {}) {
            WalletScreen(
                mode = NetworkMode.MAINNET,
                accounts = listOf(bitcoin, bitcoin.copy(index = 1, name = null, script = "Taproot"), ethereum, ethereum.copy(index = 1, name = null)),
                tokenCounts = mapOf(ethereum to 2),
                totalUsd = 5_427_212.8,
                usdOf = { null },
                showTotal = true,
                showBalances = true,
                updatedAtMs = null,
                notice = null,
                onAccount = {},
                onRefresh = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun accountPage(chain: String, account: WalletAccount, holdings: List<app.lumio.chains.ChainHolding> = emptyList()) = snap("account_$chain") {
        AccountScreen(
            walletName = walletName,
            account = account,
            showBalance = true,
            assets = AssetRows.of(account, emptyList(), holdings, emptyMap(), network = { null }, token = { _, _ -> null }),
            onSend = {},
            onReceive = {},
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun accountEthereum() = accountPage("ethereum", ethereum)

    @Test
    fun accountBitcoin() = accountPage("bitcoin", bitcoin)

    @Test
    fun accountSolana() = accountPage(
        "solana",
        solana,
        // A token the list does not know is named by its mint, in groups of four.
        listOf(app.lumio.chains.ChainHolding(ChainHoldings.mintName(MINT), "Solana Devnet", BigInteger("1500000"), 6, AssetId.Spl(SOLANA_DEVNET, MINT))),
    )

    @Test
    fun accountAlgorand() = accountPage("algorand", algorand)

    @Test
    fun addressBook() = snap("address_book") {
        AddressBookScreen(
            entries = listOf(
                AddressBookEntry("1", margaret, contactName, "Friends", group = "Friends"),
                AddressBookEntry("2", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null),
                AddressBookEntry("3", vitalik, "Exchange Deposit", "Exchanges", group = "Exchanges"),
            ),
            offline = null,
            onEdit = {},
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun sendRecipient() = snap("send_recipient") {
        SendRecipientPage(
            value = "",
            onValueChange = {},
            error = null,
            hint = null,
            canContinue = false,
            accounts = listOf(SendChoice(accountName, "Your account", own)),
            addressBook = listOf(SendChoice(contactName, "Address Book", margaret)),
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
    fun sendAmount() = snap("send_amount") {
        SendAmountPage(
            symbol = "ETH",
            toTitle = accountName,
            toDetail = "Your account",
            toAddress = own,
            fromTitle = "PulsarNova",
            fromDetail = "12.5 ETH available",
            asset = null,
            onAsset = null,
            amount = "0.25",
            onAmount = {},
            amountError = null,
            leaves = "Leaves 12.2466001 ETH after the fee",
            onUseMax = {},
            networkName = "Ethereum",
            feeText = "Up to 0.0033999 ETH",
            onFee = {},
            note = "Chosen for you: Ethereum, the network this account sends on.",
            canReview = true,
            onReview = {},
            onChangeRecipient = {},
            backLabel = "Recipient",
            offline = null,
            onBack = {},
            onLock = {},
        )
    }

    private companion object {
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val NOW_MS: Long = Instant.parse("2026-11-28T12:00:00Z").toEpochMilli()
    }
}
