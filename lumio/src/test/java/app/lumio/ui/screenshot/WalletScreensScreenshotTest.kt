package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.airvault.core.model.AccountId
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.networkId
import app.lumio.chains.ChainHolding
import app.lumio.data.Clock
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.evm.EvmChain
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.AssetRowModel
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.AssetScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One day in milliseconds, the spacing of the price points in the chart golden. */
private const val DAY_MS = 86_400_000L

/** The time the wallet goldens are drawn at, so the freshness line reads the same on every run. */
private const val FIXED_NOW = 1_731_800_000_000L

/** The clock of the goldens: [FIXED_NOW] at every read. */
private val fixedClock = Clock { FIXED_NOW }

/** A read three minutes before [FIXED_NOW], which the freshness line shows as "Updated 3 min ago". */
private const val UPDATED_3_MIN_AGO = FIXED_NOW - 3 * 60_000L

/**
 * The goldens of the wallet-first screens: the Wallets start screen, one wallet's accounts, one account and one asset. Each shows its fullest common state,
 * on the testnet side where the owner's coins are, and the edge states: an empty activity, a zero balance, hidden balances and long names. Recorded with
 * `:lumio:recordRoborazziDebug` (on the Linux runner, `record-goldens.yml`), checked with `:lumio:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val bitcoin = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(Networks.BTC_TESTNET4, "tb1qk3z8f0d6c2m9a7v4w5x1y2z3q4r5s6t7u8v9w0"),
        index = 0,
        networkName = "Bitcoin Testnet4",
        balance = NativeAsset("BTC-testnet4", "BTC", "Bitcoin Testnet4", BtcNetwork.TESTNET4.networkId(), BigDecimal("0.0012")),
        script = "Native SegWit",
    )
    private val ethereum = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(Networks.evm(10143), "0x" + "a1b2c3d4".repeat(5)),
        index = 0,
        networkName = "Monad testnet",
        balance = NativeAsset("EVM-10143", "MON", "Monad testnet", NetworkId(Networks.evm(10143), testnet = true), BigDecimal("12.5")),
    )
    private val solana = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(Networks.SOL_DEVNET, "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"),
        index = 0,
        networkName = "Solana Devnet",
        balance = NativeAsset("SOL-devnet", "SOL", "Solana Devnet", SOLANA_DEVNET, BigDecimal("0.4")),
    )
    private val algorand = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(Networks.ALGO_TESTNET, "ALGOADDRESSEXAMPLEFORSCREENSHOTONLY0000000000000000000000000"),
        index = 0,
        networkName = "Algorand Testnet",
        balance = null,
    )
    private val zeroEthereum = ethereum.copy(
        balance = NativeAsset("EVM-10143", "MON", "Monad testnet", NetworkId(Networks.evm(10143), testnet = true), BigDecimal.ZERO),
    )

    private val monad = EvmChain(10143L, "Monad testnet", "MON", 18, true, "https://testnet.monadexplorer.com")
    private val usdc = Token(10143L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
    private val usdcHolding = TokenHolding(monad, usdc, BigInteger.valueOf(1_250_500_000L), atMs = 0L)
    private val etherAsset = TxAsset(contract = null, symbol = "MON", name = "Monad", decimals = 18)
    private val usdcAsset = TxAsset(contract = usdc.contract, symbol = "USDC", name = "USD Coin", decimals = 6)
    private val splUsdc = ChainHolding("USDC", "Solana Devnet", BigInteger("1500000"), 6, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")

    /** The prices the goldens show: the Monad coin at $2.50 and the USDC token at $1. */
    private val prices: Map<AssetId, Price> = mapOf(
        AssetId.Native(NetworkId(Networks.evm(10143), testnet = true)) to Price(usd = 2.5, change24hPct = null, asOfMs = 0L, stale = false),
        AssetId.Erc20.of(NetworkId(Networks.evm(10143), testnet = true), usdc.contract) to Price(usd = 1.0, change24hPct = 0.01, asOfMs = 0L, stale = false),
    )

    /** The asset rows of [account] as the account screen lists them, priced by [prices] and with no registry logos. */
    private fun rowsOf(account: WalletAccount, tokens: List<TokenHolding> = emptyList(), holdings: List<ChainHolding> = emptyList()): List<AssetRowModel> =
        AssetRows.of(account, tokens, holdings, prices, network = { null }, token = { _, _ -> null })

    @Test
    fun wallets() = compose.lumioSnapshot("wallets", content = walletsScreen)

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun walletsLight() = compose.lumioSnapshot("wallets_light", content = walletsScreen)

    private val walletsScreen: @Composable () -> Unit = {
        WalletsScreen(
            rows = listOf(
                WalletRow("wallet-demo", "Demo", fingerprint = 0x1a2b3c4d, summary = "4 chains · 4 accounts", usd = 1234.56),
                WalletRow("wallet-second", "Savings", fingerprint = 0x5e6f7a8b, summary = "1 chain · 2 accounts", usd = 980.0),
            ),
            totalUsd = 2214.56,
            showTotal = true,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            clock = fixedClock,
        )
    }

    /** The totals are hidden: the all-wallets total and each wallet's total show the mask, and nothing moves. */
    @Test
    fun walletsHiddenTotals() = compose.lumioSnapshot("wallets_hidden") {
        WalletsScreen(
            rows = listOf(
                WalletRow("wallet-demo", "Demo", fingerprint = 0x1a2b3c4d, summary = "4 chains · 4 accounts", usd = 1234.56),
                WalletRow("wallet-second", "Savings", fingerprint = 0x5e6f7a8b, summary = "1 chain · 2 accounts", usd = 980.0),
            ),
            totalUsd = 2214.56,
            showTotal = false,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            clock = fixedClock,
        )
    }

    @Test
    fun walletsEmpty() = compose.lumioSnapshot("wallets_empty") {
        WalletsScreen(
            rows = emptyList(),
            totalUsd = null,
            showTotal = true,
            refreshing = false,
            updatedAtMs = null,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            clock = fixedClock,
        )
    }

    @Test
    fun walletsNotice() = compose.lumioSnapshot("wallets_notice") {
        WalletsScreen(
            rows = listOf(WalletRow("wallet-demo", "Demo", fingerprint = 0x1a2b3c4d, summary = "4 chains · 4 accounts", usd = null)),
            totalUsd = null,
            showTotal = true,
            refreshing = true,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = "Some balances could not be refreshed. The last known balances are shown.",
            onWallet = {},
            onPair = {},
            onRefresh = {},
            clock = fixedClock,
        )
    }

    @Test
    fun walletAccountsTestnet() = compose.lumioSnapshot("wallet_accounts_testnet") {
        WalletScreen(
            fingerprint = 0x1a2b3c4d,
            mode = NetworkMode.TESTNET,
            accounts = listOf(bitcoin, ethereum, solana, algorand),
            tokenCounts = mapOf(ethereum to 2),
            network = { null },
            totalUsd = null,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onAccount = {},
            clock = fixedClock,
        )
    }

    @Test
    fun walletAccountsHiddenWhileReading() = compose.lumioSnapshot("wallet_accounts_reading") {
        WalletScreen(
            fingerprint = 0x1a2b3c4d,
            mode = NetworkMode.MAINNET,
            accounts = listOf(ethereum.copy(networkName = "Ethereum", balance = null)),
            tokenCounts = emptyMap(),
            network = { null },
            totalUsd = null,
            showTotal = false,
            showBalances = false,
            refreshing = true,
            updatedAtMs = null,
            notice = null,
            onAccount = {},
            clock = fixedClock,
        )
    }

    /** A wallet with no account on the side: its hero says so, and no island is drawn. */
    @Test
    fun walletAccountsEmpty() = compose.lumioSnapshot("wallet_accounts_empty") {
        WalletScreen(
            fingerprint = 0x1a2b3c4d,
            mode = NetworkMode.MAINNET,
            accounts = emptyList(),
            tokenCounts = emptyMap(),
            network = { null },
            totalUsd = null,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onAccount = {},
            clock = fixedClock,
        )
    }

    /** The Wallets home before its balances are read: skeleton rows hold the places of the wallets, and the pair intro does not show. */
    @Test
    fun walletsLoading() = compose.lumioSnapshot("wallets_loading") {
        WalletsScreen(
            rows = emptyList(),
            totalUsd = null,
            showTotal = true,
            refreshing = false,
            updatedAtMs = null,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            loading = true,
            clock = fixedClock,
        )
    }

    /** A wallet name and an account network that run past the width of the screen: each truncates on one line. */
    @Test
    fun walletLongName() = compose.lumioSnapshot("wallet_long_name") {
        WalletScreen(
            fingerprint = 0x1a2b3c4d,
            mode = NetworkMode.TESTNET,
            accounts = listOf(ethereum.copy(networkName = "Monad testnet and every other EVM network this wallet reads")),
            tokenCounts = mapOf(ethereum to 12),
            network = { null },
            totalUsd = 1234.56,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onAccount = {},
            clock = fixedClock,
        )
    }

    /** An Ethereum account with one held token and two transactions: the Assets and Activity islands as the owner sees them. */
    @Test
    fun accountEthereumWithTokensAndActivity() = compose.lumioSnapshot("account_ethereum_activity", content = accountEthereumScreen)

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun accountEthereumLight() = compose.lumioSnapshot("account_ethereum_light", content = accountEthereumScreen)

    private val accountEthereumScreen: @Composable () -> Unit = {
        AccountScreen(
            walletName = "Demo",
            account = ethereum,
            showBalance = true,
            assets = rowsOf(ethereum, tokens = listOf(usdcHolding)),
            activity = ethereumActivity,
            onSend = {},
            onReceive = {},
            onOpenTransaction = {},
        )
    }

    /**
     * A Bitcoin account on its first frame. The board holds its activity from the store before the page opens, so its rows show at once, with no loading row.
     */
    @Test
    fun accountBitcoinFirstFrameWithActivity() = compose.lumioSnapshot("account_bitcoin_first_frame") {
        AccountScreen(
            walletName = "Demo",
            account = bitcoin,
            showBalance = true,
            assets = rowsOf(bitcoin),
            activity = listOf(
                accountTx(
                    1,
                    TxDirection.IN,
                    BigInteger.valueOf(120_000L),
                    "tb1qk3z8f0d6c2m9a7v4w5x1y2z3q4r5s6t7u8v9w0",
                    1_731_800_000_000L,
                    asset = TxAsset(contract = null, symbol = "BTC", name = "Bitcoin", decimals = 8),
                ),
            ),
            loadingAssets = false,
            loadingActivity = false,
            onSend = {},
            onReceive = {},
            onOpenTransaction = {},
        )
    }

    private val ethereumActivity = listOf(
        accountTx(1, TxDirection.IN, BigInteger.valueOf(1_500_000_000_000_000_000L), "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", 1_731_800_000_000L),
        accountTx(2, TxDirection.OUT, BigInteger.valueOf(250_000_000_000_000_000L), "0x2b5634C42055806a59e9107ED44D43c426E58258", 1_731_700_000_000L),
    )

    /** A zero balance: no coin in the account, so Receive is the primary action and Send drops out. */
    @Test
    fun accountZeroBalance() = compose.lumioSnapshot("account_zero_balance") {
        AccountScreen(
            walletName = "Demo",
            account = zeroEthereum,
            showBalance = true,
            assets = rowsOf(zeroEthereum),
            onSend = {},
            onReceive = {},
        )
    }

    /** An account whose tokens and activity are still being read: its native row shows, and the rows that follow hold their places as skeleton bars. */
    @Test
    fun accountLoadingReads() = compose.lumioSnapshot("account_loading") {
        AccountScreen(
            walletName = "Demo",
            account = ethereum,
            showBalance = true,
            assets = rowsOf(ethereum),
            loadingAssets = true,
            activity = emptyList(),
            loadingActivity = true,
            onSend = {},
            onReceive = {},
        )
    }

    /** A funded account with no transaction yet: the Activity island says so. */
    @Test
    fun accountEmptyActivity() = compose.lumioSnapshot("account_empty_activity") {
        AccountScreen(
            walletName = "Demo",
            account = ethereum,
            showBalance = true,
            assets = rowsOf(ethereum, tokens = listOf(usdcHolding)),
            onSend = {},
            onReceive = {},
        )
    }

    /** Hidden balances: every amount, the hero included, reads as the mask. */
    @Test
    fun accountHiddenBalances() = compose.lumioSnapshot("account_hidden_balances") {
        AccountScreen(
            walletName = "Demo",
            account = ethereum,
            showBalance = false,
            assets = rowsOf(ethereum, tokens = listOf(usdcHolding)),
            activity = ethereumActivity,
            onSend = {},
            onReceive = {},
        )
    }

    /** Long names: a wallet, a network and its address run past the width of the screen, and they truncate rather than wrap. */
    @Test
    fun accountLongNames() = compose.lumioSnapshot("account_long_names") {
        val longNetwork = EvmChain(10143L, "Monad testnet and every other EVM network this wallet reads", "MON", 18, true, monad.explorerUrl)
        val longAccount = ethereum.copy(networkName = longNetwork.name)
        AccountScreen(
            walletName = "A wallet name that runs on past the width of the screen",
            account = longAccount,
            showBalance = true,
            assets = AssetRows.of(
                longAccount,
                listOf(TokenHolding(longNetwork, usdc, BigInteger.valueOf(1_250_500_000L), atMs = 0L)),
                emptyList(),
                prices,
                network = { null },
                token = { _, _ -> null },
            ),
            activity = ethereumActivity,
            onSend = {},
            onReceive = {},
        )
    }

    @Test
    fun accountBitcoin() = compose.lumioSnapshot("account_bitcoin") {
        AccountScreen(
            walletName = "Demo",
            account = bitcoin,
            showBalance = true,
            assets = rowsOf(bitcoin),
            onSend = {},
            onReceive = {},
        )
    }

    /** A Solana account with an SPL token beside its SOL: the holding shows in the Assets island, and Activity is not read yet. */
    @Test
    fun accountSolanaSend() = compose.lumioSnapshot("account_solana") {
        AccountScreen(
            walletName = "Demo",
            account = solana,
            showBalance = true,
            assets = rowsOf(solana, holdings = listOf(splUsdc)),
            onSend = {},
            onReceive = {},
        )
    }

    /** A token's page: its amount and value, its price chart, and its own activity, with Send and Receive as the actions. */
    @Test
    fun assetToken() = compose.lumioSnapshot("asset_token") {
        AssetScreen(
            asset = rowsOf(ethereum, tokens = listOf(usdcHolding)).single { it.symbol == "USDC" },
            showBalance = true,
            chart = usdcChart,
            activity = listOf(
                accountTx(3, TxDirection.IN, BigInteger.valueOf(500_000_000L), "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", 1_731_800_000_000L, usdcAsset),
                accountTx(4, TxDirection.OUT, BigInteger.valueOf(100_000_000L), "0x2b5634C42055806a59e9107ED44D43c426E58258", 1_731_700_000_000L, usdcAsset),
            ),
            onSend = {},
            onReceive = {},
            onBack = {},
        )
    }

    /** An Algorand asset the account has not opted in to: the notice says why it cannot hold it yet, and no action is offered. */
    @Test
    fun assetAsaNotOpted() = compose.lumioSnapshot("asset_asa_not_opted_in") {
        AssetScreen(
            asset = AssetRowModel(
                assetKey = "10458941",
                symbol = "USDC",
                name = "USDC",
                network = "Algorand Testnet",
                amountText = "0",
                fiatText = null,
                icon = AssetIconSpec.Letter("U"),
                assetId = null,
                isNative = false,
                testnet = false,
                zero = true,
            ),
            showBalance = true,
            notice = "This account can't hold USDC yet. Algorand needs a one-time opt-in; 0.1 ALGO stays reserved.",
            onSend = {},
            onReceive = {},
            onBack = {},
        )
    }

    /** A week of USDC prices that rise and fall a little, for the chart. */
    private val usdcChart = PriceSeries(
        listOf(0.998, 1.001, 1.004, 0.999, 1.002, 1.006, 1.003).mapIndexed { index, usd -> PricePoint(timeMs = index * DAY_MS, usd = usd) },
    )

    /** One transaction of the account as the activity lists it: a native transfer on Monad testnet, unless [asset] says otherwise. */
    private fun accountTx(
        n: Int,
        direction: TxDirection,
        amount: BigInteger,
        counterparty: String,
        timestampMs: Long,
        asset: TxAsset = etherAsset,
    ) = TxFeedItem(
        tx = LumioTx(
            id = "EVM:10143:tx$n",
            network = NetworkId(Networks.evm(10143), testnet = true),
            account = "0x" + "ab".repeat(20),
            hash = "0x" + "%064x".format(n),
            timestampMs = timestampMs,
            asset = asset,
            amount = amount,
            direction = direction,
            fee = null,
            counterparty = counterparty,
            status = TxStatus.CONFIRMED,
            confirmations = 120L,
            inputData = null,
            memo = null,
        ),
        chain = "Monad testnet",
    )

    @Test
    fun walletAccountsWithAPrice() = compose.lumioSnapshot("wallet_accounts_priced") {
        WalletScreen(
            fingerprint = 0x1a2b3c4d,
            mode = NetworkMode.MAINNET,
            accounts = listOf(bitcoin.copy(networkName = "Bitcoin", balance = bitcoin.balance?.copy(network = BtcNetwork.MAINNET.networkId()))),
            tokenCounts = emptyMap(),
            network = { null },
            totalUsd = 1234.56,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            updatedAtMs = UPDATED_3_MIN_AGO,
            notice = null,
            onAccount = {},
            clock = fixedClock,
        )
    }
}
