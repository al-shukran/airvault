package app.lumio.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lensly.ui.theme.LenslyBlack
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSection
import app.lumio.data.config.AssetSort
import app.lumio.data.config.AssetSortKey
import app.lumio.data.config.LockSettings
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.evm.AddressRow
import app.lumio.evm.CachedBalance
import app.lumio.evm.EvmChain
import app.lumio.data.config.DisplaySettings
import app.lumio.evm.EvmSettings
import app.lumio.evm.SendAsset
import app.lumio.evm.Total
import app.lumio.evm.Totals
import app.lumio.home.HomeAsset
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallet
import app.lumio.ui.btc.BitcoinAccountScreen
import app.lumio.ui.btc.BitcoinPortfolioScreen
import app.lumio.ui.btc.BtcSendFormScreen
import app.lumio.ui.home.AssetSortSheetContent
import app.lumio.ui.home.LumioHome
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.portfolio.WalletScreen
import app.lumio.tokens.Token
import app.lumio.ui.send.SendFormScreen
import app.lumio.ui.settings.BitcoinServersSettings
import app.lumio.ui.settings.AboutSettingsPage
import app.lumio.ui.settings.AddressBookSettingsPage
import app.lumio.ui.settings.CommentBackupRows
import app.lumio.ui.settings.DisplaySettingsPage
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.NetworksSettingsPage
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import app.lumio.ui.settings.SecuritySettingsPage
import app.lumio.ui.settings.SettingsFacts
import app.lumio.ui.settings.TransactionsSettingsPage
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.settings.settingsRows
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.AccountOption
import app.lumio.home.PickTarget
import app.lumio.ui.asset.AssetScreen
import kotlin.math.sin

/**
 * The goldens of Lumio's screens, one per screen, in the Lensly dark theme at the owner's phone size. They are recorded with
 * `:lumio:recordRoborazziDebug` and checked with `:lumio:verifyRoborazziDebug`. Each screen shows its fullest common state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, true, null)
    private val chains = listOf(ethereum, sepolia)
    private val address = "0x" + "a1".repeat(20)
    private val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val assets = listOf(SendAsset(ethereum, null, null), SendAsset(ethereum, usdc, BigInteger.valueOf(1_250_500_000L)))

    @Test
    fun homeWithoutWallets() = compose.snapshot("home_empty") {
        LumioHome(
            assets = emptyList(),
            settings = DisplaySettings(),
            mode = NetworkMode.MAINNET,
            onMode = {},
            onUpdateDisplay = {},
            onLock = {},
            onAsset = {},
            onPair = {},
            hasWallets = false,
            refreshing = false,
            onRefresh = {},
        )
    }

    @Test
    fun homeTotalHidden() = compose.snapshot("home_total_hidden") {
        LumioHome(
            assets = homeAssets(),
            settings = DisplaySettings(),
            mode = NetworkMode.MAINNET,
            onMode = {},
            onUpdateDisplay = {},
            onLock = {},
            onAsset = {},
            onPair = {},
            hasWallets = true,
            refreshing = false,
            onRefresh = {},
            staleSinceMs = NOW,
        )
    }

    @Test
    fun homeTotalShown() = compose.snapshot("home_total_shown") {
        LumioHome(
            assets = homeAssets(),
            settings = DisplaySettings(showTotal = true),
            mode = NetworkMode.MAINNET,
            onMode = {},
            onUpdateDisplay = {},
            onLock = {},
            onAsset = {},
            onPair = {},
            hasWallets = true,
            refreshing = false,
            onRefresh = {},
        )
    }

    @Test
    fun homeTestnet() = compose.snapshot("home_testnet") {
        LumioHome(
            assets = testnetAssets(),
            settings = DisplaySettings(showTotal = true),
            mode = NetworkMode.TESTNET,
            onMode = {},
            onUpdateDisplay = {},
            onLock = {},
            onAsset = {},
            onPair = {},
            hasWallets = true,
            refreshing = false,
            onRefresh = {},
        )
    }

    @Test
    fun homeSortSheet() = compose.snapshot("home_sort_sheet") {
        Column(modifier = Modifier.fillMaxSize().background(LenslyBlack)) {
            AssetSortSheetContent(
                sort = AssetSort(AssetSortKey.USD_VALUE, descending = true),
                onSort = {},
                onDone = {},
            )
        }
    }

    @Test
    fun ethereumPortfolio() = compose.snapshot("ethereum_portfolio") {
        WalletScreen(
            walletName = "Demo",
            mode = NetworkMode.MAINNET,
            hasEvm = true,
            chains = chains,
            rows = listOf(AddressRow(0, address, mapOf(1L to CachedBalance("wallet-Demo", 1L, address, "1234000000000000000", 3L, NOW)))),
            totals = Totals(
                mainnet = listOf(Total("ETH", 18, BigInteger("1234000000000000000"), 1, testnet = false)),
                testnet = emptyList(),
                oldestMs = NOW,
            ),
            failures = emptyList(),
            problem = null,
            refreshing = false,
            onRefresh = {},
            onBack = {},
            onSend = {},
        )
    }

    @Test
    fun bitcoinPortfolio() = compose.snapshot("bitcoin_portfolio") {
        BitcoinPortfolioScreen(
            walletName = "Demo",
            sections = bitcoinSections(),
            scanning = false,
            progress = null,
            failures = emptyMap(),
            onOpen = {},
            onSend = {},
            onRefresh = {},
            onBack = {},
        )
    }

    @Test
    fun bitcoinAccount() = compose.snapshot("bitcoin_account") {
        BitcoinAccountScreen(
            walletName = "Demo",
            row = BtcRow(BtcAccountRef("wallet-Demo", bitcoinAccounts(BtcNetwork.MAINNET).first()), null),
            scanning = false,
            failure = null,
            onRefresh = {},
            onBack = {},
        )
    }

    @Test
    fun ethereumSendForm() = compose.snapshot("ethereum_send_form") {
        SendFormScreen(
            fromLabel = "Address 1",
            fromAddress = address,
            chains = chains,
            selectedChainId = 1L,
            onSelectChain = {},
            assets = assets,
            selectedAsset = assets.first(),
            onSelectAsset = {},
            recipient = "",
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

    /** The form as an asset page starts it: USDC chosen on Ethereum, with the recipient and the amount in USDC. */
    @Test
    fun ethereumTokenSendForm() = compose.snapshot("ethereum_token_send_form") {
        SendFormScreen(
            fromLabel = "Address 1",
            fromAddress = address,
            chains = chains,
            selectedChainId = 1L,
            onSelectChain = {},
            assets = assets,
            selectedAsset = assets[1],
            onSelectAsset = {},
            recipient = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
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

    @Test
    fun bitcoinSendForm() = compose.snapshot("bitcoin_send_form") {
        val refs = bitcoinAccounts(BtcNetwork.MAINNET).map { BtcAccountRef("wallet-Demo", it) }
        BtcSendFormScreen(
            accounts = refs,
            selectedKey = refs.first().key,
            onSelect = {},
            recipient = "",
            onRecipient = {},
            onScan = {},
            amount = "",
            onAmount = {},
            feeRate = "",
            onFeeRate = {},
            feeSuggestion = "Suggested 12 sat/vB for the next block",
            busy = false,
            notice = null,
            onReview = {},
            onBack = {},
        )
    }

    /** The top-level list: one row per page with its state, and the Developer row last, as the debug build shows it. */
    @Test
    fun settings() = compose.snapshot("settings") {
        LumioSettingsScreen(
            rows = settingsRows(
                facts = SettingsFacts(
                    display = DisplaySettings(),
                    chainsOn = 3,
                    customBitcoin = false,
                    providersOn = 9,
                    providersTotal = 11,
                    wallets = 1,
                    addresses = 2,
                ),
                gesture = EntryGesture.DOUBLE_TAP_HOLD,
                version = "0.1.0",
                developer = "Demo portfolio on",
            ),
            onOpen = {},
        )
    }

    @Test
    fun settingsDisplay() = compose.snapshot("settings_display") {
        DisplaySettingsPage(
            settings = DisplaySettings(showTotal = true),
            notice = null,
            onShowTotal = {},
            onShowAssets = {},
            onMinimumUsd = {},
            onBack = {},
        )
    }

    @Test
    fun settingsTransactions() = compose.snapshot("settings_transactions") {
        TransactionsSettingsPage(
            hideSpam = true,
            notice = null,
            onHideSpam = {},
            onBack = {},
            comments = { CommentBackupRows(onExport = {}, onImport = {}, notice = null) },
        )
    }

    @Test
    fun settingsNetworks() = compose.snapshot("settings_networks") {
        NetworksSettingsPage(
            onBack = {},
            bitcoin = {
                BitcoinServersSettings(
                    mainnet = "https://mempool.space/api",
                    testnet4 = "https://mempool.space/testnet4/api",
                    error = null,
                    onApply = { _, _ -> },
                    onReset = {},
                )
            },
            ethereum = {
                EvmSettingsSection(
                    settings = EvmSettings(),
                    chains = chains,
                    busy = false,
                    notice = null,
                    onAddressCount = {},
                    onEnabled = { _, _ -> },
                    onSetRpc = { _, _, _ -> },
                    onResetRpc = {},
                    onRemoveCustom = {},
                    onAddCustom = { _, _ -> },
                )
            },
        )
    }

    @Test
    fun settingsAddressBook() = compose.snapshot("settings_address_book") {
        AddressBookSettingsPage(
            onBack = {},
            wallets = {
                WalletsSettings(
                    wallets = listOf(PairedWallet(id = "wallet-Demo", name = "Demo", fingerprint = 0x1a2b3c4dL, createdAtMs = NOW, evm = null, bitcoin = emptyList())),
                    onPair = {},
                    onAddressBook = {},
                    onDelete = {},
                )
            },
        )
    }

    @Test
    fun settingsSecurity() = compose.snapshot("settings_security") {
        SecuritySettingsPage(
            entryGesture = EntryGesture.THUMBNAIL_HOLD_SWIPE,
            lock = LockSettings(),
            onEntryGesture = {},
            onIdleMinutes = {},
            onLockWhenSwitching = {},
            onBack = {},
        )
    }

    @Test
    fun settingsSecuritySwitching() = compose.snapshot("settings_security_switching") {
        SecuritySettingsPage(
            entryGesture = EntryGesture.THUMBNAIL_HOLD_SWIPE,
            lock = LockSettings(idleMinutes = 5, lockWhenSwitchingApps = true),
            onEntryGesture = {},
            onIdleMinutes = {},
            onLockWhenSwitching = {},
            onBack = {},
        )
    }

    @Test
    fun settingsAbout() = compose.snapshot("settings_about") {
        AboutSettingsPage(version = "0.1.0", onLicences = {}, onBack = {})
    }

    @Test
    fun dataProviders() = compose.snapshot("data_providers") {
        DataProvidersScreen(
            data = listOf(
                providerRow("etherscan", acceptsKey = true, status = ProviderStatus.NeedsKey, canMoveUp = false),
                providerRow("mempool", acceptsKey = false, status = ProviderStatus.Ready, canMoveDown = false),
            ),
            rpc = listOf(
                providerRow("1rpc", acceptsKey = true, mev = true, status = ProviderStatus.Healthy(120.0), apiKey = "k-1"),
            ),
            onBack = {},
            onEnabled = { _, _ -> },
            onMove = { _, _ -> },
            onApiKey = { _, _ -> },
        )
    }

    /** The Prices group alone, first on screen so the capture shows it: CoinGecko needs its key, DefiLlama is keyless and healthy. */
    @Test
    fun dataProvidersPrices() = compose.snapshot("data_providers_prices") {
        DataProvidersScreen(
            data = emptyList(),
            prices = listOf(
                providerRow("coingecko", acceptsKey = true, status = ProviderStatus.NeedsKey, canMoveUp = false),
                providerRow("defillama", acceptsKey = false, status = ProviderStatus.Healthy(95.0), canMoveDown = false),
            ),
            rpc = emptyList(),
            onBack = {},
            onEnabled = { _, _ -> },
            onMove = { _, _ -> },
            onApiKey = { _, _ -> },
        )
    }

    @Test
    fun pairReview() = compose.snapshot("pair_review") {
        PairReviewScreen(
            pairing = PairingPayload(
                fingerprint = 0x1a2b3c4dL,
                name = "Demo",
                apps = listOf(
                    PairingApp(
                        app = PairImport.ETHEREUM,
                        keys = listOf(PairingKey.ethereumAccount(HdWallet(PairFixtures.seed).accountXpub())),
                        accounts = listOf(PairingAccount(key = 0, child = 0, name = null, label = null, group = null)),
                    ),
                ),
            ),
            alreadyPaired = false,
            error = null,
            onSave = {},
            onCancel = {},
        )
    }

    /** The Ethereum account of the demo wallet, which the asset page's Send and Receive take at once. */
    private val demoWallet = AccountOption(PickTarget.Evm("wallet-Demo"), "Ethereum", "Demo")

    /** A coin's page with its chart over a week, its transactions, and its Send and Receive. */
    @Test
    fun assetCoin() = compose.snapshot("asset_coin") {
        AssetScreen(
            asset = NativeAsset("EVM-1", "ETH", "Ethereum", NetworkId(Family.EVM, "1", testnet = false), BigDecimal("1.2345"), 3950.12, 1.25),
            entry = null,
            hidden = false,
            range = ChartRange.D7,
            onRange = {},
            series = assetSeries(base = 3950.0, amplitude = 120.0, points = 43, spacingMs = 14_400_000L),
            chartLoading = false,
            transactions = listOf(
                assetTx(1, TxDirection.IN, "500000000000000000", NOW - 3_600_000L),
                assetTx(2, TxDirection.OUT, "250000000000000000", NOW - 90_000_000L),
            ),
            sendOptions = listOf(demoWallet),
            receiveOptions = listOf(demoWallet),
            accounts = listOf(demoWallet),
            onSend = {},
            onReceive = {},
            onAccount = {},
            onBack = {},
        )
    }

    /** A token's page: its chart over a month, its transaction, and Send and Receive, both from the account that holds it. */
    @Test
    fun assetToken() = compose.snapshot("asset_token") {
        AssetScreen(
            asset = TokenAsset(
                id = TokenAsset.idOf(NetworkId(Family.EVM, "1", testnet = false), usdcMainnet),
                symbol = "USDC",
                name = "Ethereum",
                network = NetworkId(Family.EVM, "1", testnet = false),
                contract = usdcMainnet,
                balance = BigDecimal("1250.5"),
                usd = 1250.5,
                change24h = 0.02,
            ),
            entry = null,
            hidden = false,
            range = ChartRange.D30,
            onRange = {},
            series = assetSeries(base = 1.0, amplitude = 0.002, points = 31, spacingMs = 86_400_000L),
            chartLoading = false,
            transactions = listOf(
                assetTx(3, TxDirection.IN, "1250500000", NOW - 7_200_000L, TxAsset(usdcMainnet, "USDC", "USD Coin", 6)),
            ),
            sendOptions = listOf(demoWallet),
            receiveOptions = listOf(demoWallet),
            accounts = listOf(demoWallet),
            onSend = {},
            onReceive = {},
            onAccount = {},
            onBack = {},
        )
    }

    /** A testnet coin, which has no price: the chart says there is no price data, and the balance has no value. */
    @Test
    fun assetNoPrice() = compose.snapshot("asset_no_price") {
        AssetScreen(
            asset = NativeAsset("EVM-11155111", "ETH", "Sepolia", NetworkId(Family.EVM, "11155111", testnet = true), BigDecimal("0.25")),
            entry = null,
            hidden = false,
            range = ChartRange.H24,
            onRange = {},
            series = null,
            chartLoading = false,
            transactions = emptyList(),
            sendOptions = listOf(demoWallet),
            receiveOptions = listOf(demoWallet),
            accounts = listOf(demoWallet),
            onSend = {},
            onReceive = {},
            onAccount = {},
            onBack = {},
        )
    }

    /** A wave of prices around [base], one every [spacingMs], for the chart fixtures. */
    private fun assetSeries(base: Double, amplitude: Double, points: Int, spacingMs: Long): PriceSeries = PriceSeries(
        (0 until points).map { i -> PricePoint(NOW - (points - 1 - i) * spacingMs, base + amplitude * sin(i / 4.0)) },
    )

    /** A transaction of the Ethereum coin, or of [asset], on the demo wallet's address. */
    private fun assetTx(
        id: Int,
        direction: TxDirection,
        amountBaseUnits: String,
        timestampMs: Long,
        asset: TxAsset = TxAsset(null, "ETH", "Ether", 18),
    ) = LumioTx(
        id = "tx-$id",
        network = NetworkId(Family.EVM, "1", testnet = false),
        account = address,
        hash = "0x" + "%064x".format(id),
        timestampMs = timestampMs,
        asset = asset,
        amount = BigInteger(amountBaseUnits),
        direction = direction,
        fee = null,
        counterparty = "0x" + "b2".repeat(20),
        status = TxStatus.CONFIRMED,
        confirmations = 100,
        inputData = null,
        memo = null,
    )

    /** The USDC contract on Ethereum, which Home lists as a token. */
    private val usdcMainnet = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

    /** The coins and a token of a mainnet side: one priced below the minimum, which Home hides. */
    private fun homeAssets(): List<HomeAsset> = listOf(
        NativeAsset("BTC-mainnet", "BTC", "Bitcoin", NetworkId(Family.BTC, "mainnet", testnet = false), BigDecimal("0.0425"), 4012.5, -0.4),
        NativeAsset("EVM-1", "ETH", "Ethereum", NetworkId(Family.EVM, "1", testnet = false), BigDecimal("1.2345"), 3950.12, 1.25),
        NativeAsset("EVM-8453", "ETH", "Base", NetworkId(Family.EVM, "8453", testnet = false), BigDecimal("0.5"), 1975.0, 1.25),
        NativeAsset("EVM-10", "ETH", "Optimism", NetworkId(Family.EVM, "10", testnet = false), BigDecimal("0.0001"), 0.3, 0.1),
        TokenAsset(
            id = TokenAsset.idOf(NetworkId(Family.EVM, "1", testnet = false), usdcMainnet),
            symbol = "USDC",
            name = "Ethereum",
            network = NetworkId(Family.EVM, "1", testnet = false),
            contract = usdcMainnet,
            balance = BigDecimal("1250.5"),
            usd = 1250.5,
            change24h = 0.02,
        ),
    )

    /** The coins of a testnet side, which have no price. */
    private fun testnetAssets(): List<HomeAsset> = listOf(
        NativeAsset("EVM-11155111", "ETH", "Sepolia", NetworkId(Family.EVM, "11155111", testnet = true), BigDecimal("0.25")),
        NativeAsset("EVM-10143", "MON", "Monad testnet", NetworkId(Family.EVM, "10143", testnet = true), BigDecimal("12")),
        NativeAsset("BTC-testnet4", "BTC", "Bitcoin testnet4", NetworkId(Family.BTC, "testnet4", testnet = true), BigDecimal("0.0002")),
    )

    /** The Bitcoin accounts of [network]: the four script types of the seed. */
    private fun bitcoinAccounts(network: BtcNetwork): List<BtcWatchOnlyAccount> = BtcWatchOnly.accounts(PairFixtures.seed, network)

    private fun bitcoinSections(): List<BtcSection> = listOf(
        BtcSection(BtcNetwork.MAINNET, bitcoinAccounts(BtcNetwork.MAINNET).map { BtcRow(BtcAccountRef("wallet-Demo", it), null) }),
        BtcSection(BtcNetwork.TESTNET4, bitcoinAccounts(BtcNetwork.TESTNET4).map { BtcRow(BtcAccountRef("wallet-Demo", it), null) }),
    )

    private fun providerRow(
        id: String,
        acceptsKey: Boolean,
        status: ProviderStatus,
        mev: Boolean = false,
        apiKey: String? = null,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
    ) = ProviderRow(
        id = id,
        mevProtected = mev,
        acceptsKey = acceptsKey,
        enabled = true,
        apiKey = apiKey,
        status = status,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )

    private companion object {
        /** A fixed time for the reads, so the goldens do not change from day to day. */
        const val NOW = 1_800_000_000_000L
    }
}
