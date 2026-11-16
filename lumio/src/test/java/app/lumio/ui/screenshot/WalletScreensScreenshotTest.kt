package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lumio.btc.networkId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.home.NativeAsset
import app.lumio.network.NetworkMode
import app.lumio.ui.wallets.AccountScreen
import app.lumio.ui.wallets.WalletRow
import app.lumio.ui.wallets.WalletScreen
import app.lumio.ui.wallets.WalletsScreen
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import app.lensly.core.btc.BtcNetwork
import java.math.BigDecimal
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
    fun accountSolanaSendComingSoon() = compose.snapshot("account_solana") {
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
