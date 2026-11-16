package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSection
import app.lumio.evm.AddressRow
import app.lumio.evm.CachedBalance
import app.lumio.evm.EvmChain
import app.lumio.data.config.DisplaySettings
import app.lumio.evm.EvmSettings
import app.lumio.evm.Total
import app.lumio.evm.Totals
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallet
import app.lumio.ui.btc.BitcoinAccountScreen
import app.lumio.ui.btc.BitcoinPortfolioScreen
import app.lumio.ui.btc.BtcSendFormScreen
import app.lumio.ui.home.LumioHome
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.portfolio.WalletScreen
import app.lumio.ui.send.SendFormScreen
import app.lumio.ui.settings.BitcoinServersSettings
import app.lumio.ui.settings.DisplaySettingsSection
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @Test
    fun homeWithoutWallets() = compose.snapshot("home_empty") {
        LumioHome(onLock = {}, onPair = {}, wallets = emptyList(), onDelete = {}, mode = NetworkMode.MAINNET, onMode = {})
    }

    @Test
    fun homeWithAWalletOnMainnet() = compose.snapshot("home_wallet_mainnet") {
        LumioHome(onLock = {}, onPair = {}, wallets = listOf(demoWallet()), onDelete = {}, mode = NetworkMode.MAINNET, onMode = {})
    }

    @Test
    fun homeWithAWalletOnTestnet() = compose.snapshot("home_wallet_testnet") {
        LumioHome(onLock = {}, onPair = {}, wallets = listOf(demoWallet()), onDelete = {}, mode = NetworkMode.TESTNET, onMode = {})
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
            recipient = "",
            onRecipient = {},
            amount = "",
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

    @Test
    fun settings() = compose.snapshot("settings") {
        LumioSettingsScreen(
            onBack = {},
            display = {
                DisplaySettingsSection(
                    settings = DisplaySettings(),
                    notice = null,
                    onShowTotal = {},
                    onShowAssets = {},
                    onMinimumUsd = {},
                    onHideSpam = {},
                )
            },
            bitcoin = {
                BitcoinServersSettings(
                    mainnet = "https://mempool.space/api",
                    testnet4 = "https://mempool.space/testnet4/api",
                    error = null,
                    onSave = { _, _ -> },
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
            onDataProviders = {},
            onThirdPartyLicences = {},
        )
    }

    /** The Entry section sits below the fold, so this golden scrolls it into view before the capture. */
    @Test
    fun settingsEntryGesture() = compose.snapshot(
        name = "settings_entry_gesture",
        beforeCapture = { onNodeWithText("Hidden entry gesture").performScrollTo() },
    ) {
        LumioSettingsScreen(
            onBack = {},
            display = {},
            bitcoin = {},
            ethereum = {},
            onDataProviders = {},
            onThirdPartyLicences = {},
            entryGesture = EntryGesture.THUMBNAIL_HOLD_SWIPE,
            onEntryGesture = {},
        )
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

    private fun demoWallet(): PairedWallet = PairedWallet(
        id = "wallet-Demo",
        name = "Demo",
        fingerprint = 0x1a2b3c4dL,
        createdAtMs = 1L,
        evm = HdWallet(PairFixtures.seed).accountXpub(),
        bitcoin = bitcoinAccounts(BtcNetwork.MAINNET) + bitcoinAccounts(BtcNetwork.TESTNET4),
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
