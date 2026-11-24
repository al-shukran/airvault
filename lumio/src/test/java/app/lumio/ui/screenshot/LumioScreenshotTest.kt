package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.export.PairingPayload
import app.lensly.ui.components.groupAddress
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.flow.PasswordCheck
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.settings.ChangePasswordScreen
import app.lensly.ui.settings.ChangePasswordState
import app.lensly.ui.settings.ChangeStep
import app.lumio.btc.BtcAccountRef
import app.lumio.data.config.AutoLock
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FinishSetup
import app.lumio.data.config.LockSettings
import app.lumio.data.config.TransactionSettings
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.home.TokenAsset
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import app.lumio.ui.send.SendAmountPage
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.settings.AboutSettingsPage
import app.lumio.ui.settings.AddressBookRows
import app.lumio.ui.settings.AddressBookSettingsPage
import app.lumio.ui.settings.BiometricSwitch
import app.lumio.ui.settings.BitcoinServersSettings
import app.lumio.ui.settings.CommentBackupRows
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.DeviceProtectionSettingsPage
import app.lumio.ui.settings.DisplaySettingsPage
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.LumioSettingsScreen
import app.lumio.ui.settings.NetworksSettingsPage
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import app.lumio.ui.settings.SecuritySettingsPage
import app.lumio.ui.settings.SettingsFacts
import app.lumio.ui.settings.TransactionsSettingsPage
import app.lumio.ui.settings.WalletsSettings
import app.lumio.ui.settings.WalletsSettingsPage
import app.lumio.ui.settings.settingsRows
import java.io.File
import java.math.BigInteger
import kotlin.math.sin
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
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, true, null)
    private val chains = listOf(ethereum, sepolia)
    private val address = "0x" + "a1".repeat(20)

    @Test
    fun bitcoinSendForm() = compose.lumioSnapshot("bitcoin_send_form") {
        SendAmountPage(
            symbol = "BTC",
            toTitle = groupAddress(BTC_RECIPIENT),
            toDetail = "",
            toAddress = BTC_RECIPIENT,
            fromTitle = "Native SegWit",
            fromDetail = "0.0150 BTC available",
            asset = null,
            onAsset = null,
            amount = "0.0001",
            onAmount = {},
            amountError = null,
            leaves = "Leaves 0.0148 BTC after the fee",
            onUseMax = null,
            networkName = "Bitcoin Mainnet",
            feeText = "0.00000208 BTC",
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

    /** The App Password change screen as Lumio opens it. The screen takes no input in a golden, so its callbacks do nothing. */
    @Composable
    private fun LumioChangePasswordScreen(state: ChangePasswordState) {
        ChangePasswordScreen(state = state, onKey = {}, onBackspace = {}, onSubmit = {}, onBack = {}, onDone = {})
    }

    /** The top-level list: one row per page with its state, and the Developer row last, as the debug build shows it. */
    @Test
    fun settings() = compose.lumioSnapshot("settings", content = settingsScreen)

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun settingsLight() = compose.lumioSnapshot("settings_light", content = settingsScreen)

    private val settingsScreen: @Composable () -> Unit = {
        LumioSettingsScreen(
            rows = settingsRows(
                facts = SettingsFacts(
                    display = DisplaySettings(),
                    transactions = TransactionSettings(),
                    chainsOn = 3,
                    customBitcoin = false,
                    providersOn = 9,
                    providersTotal = 11,
                    wallets = 1,
                    addresses = 2,
                    finish = FinishSetup(),
                ),
                gesture = EntryGesture.DOUBLE_TAP_HOLD,
                version = "0.1.0",
            ),
            finish = FinishSetup(),
            onFinish = {},
            onOpen = {},
            onReset = {},
        )
    }

    @Test
    fun settingsDisplay() = compose.lumioSnapshot("settings_display") {
        DisplaySettingsPage(
            settings = DisplaySettings(showTotal = true),
            notice = null,
            onShowTotal = {},
            onShowAssets = {},
            onCurrency = {},
            onSmallBalance = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun settingsDisplayEuro() = compose.lumioSnapshot("settings_display_euro") {
        DisplaySettingsPage(
            settings = DisplaySettings(currency = FiatCurrency.EUR, smallBalanceUsd = 5.0),
            notice = null,
            onShowTotal = {},
            onShowAssets = {},
            onCurrency = {},
            onSmallBalance = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun settingsTransactions() = compose.lumioSnapshot("settings_transactions") {
        TransactionsSettingsPage(
            settings = TransactionSettings(),
            notice = null,
            onHideSpam = {},
            onLastChance = {},
            offline = null, onLock = {}, onBack = {},
            comments = { CommentBackupRows(onExport = {}, onImport = {}, notice = null) },
        )
    }

    @Test
    fun settingsTransactionsSpamShownSendAtOnce() = compose.lumioSnapshot("settings_transactions_spam_shown") {
        TransactionsSettingsPage(
            settings = TransactionSettings(hideSpam = false, lastChanceSeconds = 0),
            notice = null,
            onHideSpam = {},
            onLastChance = {},
            offline = null, onLock = {}, onBack = {},
            comments = { CommentBackupRows(onExport = {}, onImport = {}, notice = null) },
        )
    }

    @Test
    fun settingsNetworks() = compose.lumioSnapshot("settings_networks") {
        NetworksSettingsPage(
            offline = null, onLock = {}, onBack = {},
            bitcoin = {
                BitcoinServersSettings(
                    mainnet = "https://mempool.space/api",
                    testnet4 = "https://mempool.space/testnet4/api",
                    indexer = "",
                    error = null,
                    onApply = { _, _ -> },
                    onIndexer = {},
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
                    onAddNetwork = {},
                )
            },
        )
    }

    @Test
    fun settingsWallets() = compose.lumioSnapshot("settings_wallets") {
        WalletsSettingsPage(
            offline = null, onLock = {}, onBack = {},
            wallets = {
                WalletsSettings(
                    wallets = listOf(PairedWallet(id = "wallet-Demo", name = "Demo", fingerprint = 0x1a2b3c4dL, createdAtMs = NOW, evmAccounts = emptyList(), bitcoin = emptyList())),
                    onPair = {},
                    onDelete = {},
                )
            },
        )
    }

    @Test
    fun settingsAddressBook() = compose.lumioSnapshot("settings_address_book") {
        AddressBookSettingsPage(
            offline = null, onLock = {}, onBack = {},
            wallets = {
                AddressBookRows(
                    wallets = listOf(PairedWallet(id = "wallet-Demo", name = "Demo", fingerprint = 0x1a2b3c4dL, createdAtMs = NOW, evmAccounts = emptyList(), bitcoin = emptyList())),
                    onAddressBook = {},
                )
            },
        )
    }

    @Test
    fun settingsSecurity() = compose.lumioSnapshot("settings_security") {
        SecuritySettingsPage(
            entryGesture = EntryGesture.THUMBNAIL_HOLD_SWIPE,
            lock = LockSettings(),
            deviceProtectionOn = false,
            onEntryGesture = {},
            onChangePassword = {},
            onDeviceProtection = {},
            checkPassword = { PasswordCheck.Accepted },
            onAutoLock = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun settingsSecuritySwitching() = compose.lumioSnapshot("settings_security_switching") {
        SecuritySettingsPage(
            entryGesture = EntryGesture.THUMBNAIL_HOLD_SWIPE,
            lock = LockSettings(AutoLock.IMMEDIATELY),
            deviceProtectionOn = false,
            onEntryGesture = {},
            onChangePassword = {},
            onDeviceProtection = {},
            checkPassword = { PasswordCheck.Accepted },
            onAutoLock = {},
            offline = null, onLock = {}, onBack = {},
        )
    }

    @Test
    fun settingsDeviceProtection() = compose.lumioSnapshot("settings_device_protection") {
        DeviceProtectionSettingsPage(biometric = BiometricSwitch(on = false, canEnable = true), onBiometric = {}, offline = null, onLock = {}, onBack = {})
    }

    @Test
    fun settingsDeviceProtectionUnavailable() = compose.lumioSnapshot("settings_device_protection_unavailable") {
        DeviceProtectionSettingsPage(biometric = BiometricSwitch(on = false, canEnable = false), onBiometric = {}, offline = null, onLock = {}, onBack = {})
    }

    /** The App Password change that Security opens: its first step, a refused new password, and the finished change. */
    @Test
    fun settingsChangePassword() = compose.lumioSnapshot("settings_change_password") {
        LumioChangePasswordScreen(ChangePasswordState(step = ChangeStep.Current, length = 6))
    }

    @Test
    fun settingsChangePasswordRefused() = compose.lumioSnapshot("settings_change_password_refused") {
        LumioChangePasswordScreen(ChangePasswordState(step = ChangeStep.New, length = 4, error = PolicyError.TooShort))
    }

    @Test
    fun settingsChangePasswordChanged() = compose.lumioSnapshot("settings_change_password_changed") {
        LumioChangePasswordScreen(ChangePasswordState(changed = true))
    }

    @Test
    fun settingsAbout() = compose.lumioSnapshot("settings_about") {
        AboutSettingsPage(version = "0.1.0", onLicences = {}, offline = null, onLock = {}, onBack = {})
    }

    @Test
    fun dataProviders() = compose.lumioSnapshot("data_providers") {
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
    fun dataProvidersPrices() = compose.lumioSnapshot("data_providers_prices") {
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
    fun pairReview() = compose.lumioSnapshot("pair_review") {
        PairReviewScreen(
            pairing = PairingPayload(
                fingerprint = 0x1a2b3c4dL,
                name = "Demo",
                autoNameBase = "Demo",
                accounts = listOf(PairFixtures.ethereumAccount(0, "Demo 0")),
            ),
            alreadyPaired = false,
            error = null,
            onSave = {},
            onLock = {},
            onCancel = {},
        )
    }

    /** The Ethereum account of the demo wallet, which the asset page's Send and Receive take at once. */

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
        network = NetworkId(Networks.evm(1), testnet = false),
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

    /** The registry of the shared assets, read the way the registry tests read it, so Home's goldens show the logos the app ships. */
    private val homeRegistry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets")
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?.readText()
    }

    /** The registry's token entries by asset ID, as Home keeps them. */
    private val homeTokenEntries: Map<String, TokenEntry> = homeRegistry.tokens().associateBy {
        TokenAsset.idOf(NetworkId(Networks.evm(it.chainId), testnet = false), it.contract)
    }

    /** The Bitcoin accounts of [network]: the four script types of the seed. */
    private fun bitcoinAccounts(network: BtcNetwork): List<BtcWatchOnlyAccount> = BtcWatchOnly.accounts(PairFixtures.seed, network)

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

private const val BTC_RECIPIENT = "bc1q9h7w3kx0g2lz8f5y6v4r0d8qsm3p2a7kxw5e"
