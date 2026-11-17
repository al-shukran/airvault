package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lumio.btc.networkId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
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
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import app.lensly.core.btc.BtcNetwork
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the wallet-first screens: the Wallets start screen, one wallet's accounts, and one account. Each shows its fullest common state, on
 * the testnet side where the owner's coins are. Recorded with `:lumio:recordRoborazziDebug`, checked with `:lumio:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletScreensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val bitcoin = WalletAccount(
        walletId = "wallet-demo",
        chain = AccountChain.BITCOIN,
        index = 0,
        address = "tb1qk3z8f0d6c2m9a7v4w5x1y2z3q4r5s6t7u8v9w0",
        networkName = "Bitcoin testnet4",
        balance = NativeAsset("BTC-testnet4", "BTC", "Bitcoin testnet4", BtcNetwork.TESTNET4.networkId(), BigDecimal("0.0012")),
        script = "native segwit",
    )
    private val ethereum = WalletAccount(
        walletId = "wallet-demo",
        chain = AccountChain.ETHEREUM,
        index = 0,
        address = "0x" + "a1b2c3d4".repeat(5),
        networkName = "Monad testnet",
        balance = NativeAsset("EVM-10143", "MON", "Monad testnet", NetworkId(Family.EVM, "10143", testnet = true), BigDecimal("12.5")),
    )
    private val solana = WalletAccount(
        walletId = "wallet-demo",
        chain = AccountChain.SOLANA,
        index = 0,
        address = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU",
        networkName = "Solana devnet",
        balance = NativeAsset("SOL-devnet", "SOL", "Solana devnet", SOLANA_DEVNET, BigDecimal("0.4")),
    )
    private val algorand = WalletAccount(
        walletId = "wallet-demo",
        chain = AccountChain.ALGORAND,
        index = 0,
        address = "ALGOADDRESSEXAMPLEFORSCREENSHOTONLY0000000000000000000000000",
        networkName = "Algorand testnet",
        balance = null,
    )

    @Test
    fun wallets() = compose.snapshot("wallets") {
        WalletsScreen(
            rows = listOf(
                WalletRow("wallet-demo", "Demo", "1 Bitcoin, 1 Ethereum, 1 Solana, 1 Algorand"),
                WalletRow("wallet-second", "Second wallet", "2 Ethereum"),
            ),
            refreshing = false,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            onLock = {},
        )
    }

    @Test
    fun walletsEmpty() = compose.snapshot("wallets_empty") {
        WalletsScreen(
            rows = emptyList(),
            refreshing = false,
            notice = null,
            onWallet = {},
            onPair = {},
            onRefresh = {},
            onLock = {},
        )
    }

    @Test
    fun walletsNotice() = compose.snapshot("wallets_notice") {
        WalletsScreen(
            rows = listOf(WalletRow("wallet-demo", "Demo", "1 Bitcoin, 1 Ethereum")),
            refreshing = true,
            notice = "Some balances could not be refreshed. The last known balances are shown.",
            onWallet = {},
            onPair = {},
            onRefresh = {},
            onLock = {},
        )
    }

    @Test
    fun walletAccountsTestnet() = compose.snapshot("wallet_accounts_testnet") {
        WalletScreen(
            walletName = "Demo",
            mode = NetworkMode.TESTNET,
            accounts = listOf(bitcoin, ethereum, solana, algorand),
            totalUsd = null,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            notice = null,
            onAccount = {},
            onRefresh = {},
            onBack = {},
        )
    }

    @Test
    fun walletAccountsHiddenWhileReading() = compose.snapshot("wallet_accounts_reading") {
        WalletScreen(
            walletName = "Demo",
            mode = NetworkMode.MAINNET,
            accounts = listOf(ethereum.copy(networkName = "Ethereum", balance = null)),
            totalUsd = null,
            showTotal = false,
            showBalances = false,
            refreshing = true,
            notice = null,
            onAccount = {},
            onRefresh = {},
            onBack = {},
        )
    }

    /** An Ethereum account with one held token and two transactions: the Tokens and Activity sections as the owner sees them. */
    @Test
    fun accountEthereumWithTokensAndActivity() = compose.snapshot("account_ethereum_activity") {
        val chain = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
        val usdc = Token(1L, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6)
        AccountScreen(
            walletName = "Demo",
            account = ethereum,
            showBalance = true,
            tokens = listOf(TokenHolding(chain, usdc, BigInteger.valueOf(1_250_500_000L), atMs = 0L)),
            activity = listOf(
                accountTx(1, TxDirection.IN, BigInteger.valueOf(1_500_000_000_000_000_000L), "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", 1_731_800_000_000L),
                accountTx(2, TxDirection.OUT, BigInteger.valueOf(250_000_000_000_000_000L), "0x2b5634C42055806a59e9107ED44D43c426E58258", 1_731_700_000_000L),
            ),
            onSend = {},
            onReceive = {},
            onOpenTransaction = {},
            onBack = {},
        )
    }

    /** One native Ethereum transaction of the account, as the activity lists it. */
    private fun accountTx(n: Int, direction: TxDirection, amount: BigInteger, counterparty: String, timestampMs: Long) = TxFeedItem(
        tx = LumioTx(
            id = "EVM:1:tx$n",
            network = NetworkId(Family.EVM, "1", false),
            account = "0x" + "ab".repeat(20),
            hash = "0x" + "%064x".format(n),
            timestampMs = timestampMs,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = amount,
            direction = direction,
            fee = null,
            counterparty = counterparty,
            status = TxStatus.CONFIRMED,
            confirmations = 120L,
            inputData = null,
            memo = null,
        ),
        chain = "Ethereum",
    )

    @Test
    fun accountBitcoin() = compose.snapshot("account_bitcoin") {
        AccountScreen(
            walletName = "Demo",
            account = bitcoin,
            showBalance = true,
            onSend = {},
            onReceive = {},
            onBack = {},
        )
    }

    @Test
    fun accountSolanaSend() = compose.snapshot("account_solana") {
        AccountScreen(
            walletName = "Demo",
            account = solana,
            showBalance = true,
            onSend = {},
            onReceive = {},
            onBack = {},
        )
    }

    @Test
    fun walletAccountsWithAPrice() = compose.snapshot("wallet_accounts_priced") {
        WalletScreen(
            walletName = "Demo",
            mode = NetworkMode.MAINNET,
            accounts = listOf(bitcoin.copy(networkName = "Bitcoin", balance = bitcoin.balance?.copy(network = BtcNetwork.MAINNET.networkId()))),
            totalUsd = 1234.56,
            showTotal = true,
            showBalances = true,
            refreshing = false,
            notice = null,
            onAccount = {},
            onRefresh = {},
            onBack = {},
        )
    }
}
