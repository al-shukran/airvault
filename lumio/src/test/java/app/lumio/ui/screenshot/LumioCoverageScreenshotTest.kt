package app.lumio.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.screenshot.SCREENSHOT_DIR
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lensly.ui.settings.NoticeBlock
import app.lensly.ui.settings.ThirdPartyLicencesScreen
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslyTheme
import app.lumio.btc.BtcSend
import app.lumio.btc.BtcSendPlan
import app.lumio.btc.BtcSpendable
import app.lumio.evm.CustomChain
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSend
import app.lumio.evm.PreparedSend
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.ui.btc.BtcSendReviewScreen
import app.lumio.ui.btc.BtcSendSentScreen
import app.lumio.ui.btc.BtcSignRequestScreen
import app.lumio.ui.network.NetworkSwitch
import app.lumio.ui.network.TestnetBanner
import app.lumio.ui.pair.PairReviewScreen
import app.lumio.ui.pair.PairScanScreen
import app.lumio.ui.send.SendSentScreen
import app.lumio.ui.send.SendReviewScreen
import app.lumio.ui.send.SignRequestScreen
import app.lumio.ui.send.SignatureScanScreen
import app.lumio.ui.settings.DataProvidersScreen
import app.lumio.ui.settings.EvmSettingsSection
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.app.export.partsOf
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Lumio's screens that [LumioScreenshotTest] does not cover: the Ethereum settings and the custom chain dialogs, the provider
 * statuses, the licences, the network controls, the review and broadcast states of a Bitcoin and an Ethereum send, and the pairing scan and
 * import results. They use fake state and the same theme, size and recording as [LumioScreenshotTest], and they are recorded with
 * `:lumio:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumioCoverageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, true, null)
    private val demo = EvmChain(424242L, "Demo Chain", "DEMO", 18, false, null)
    private val chains = listOf(ethereum, sepolia, demo)
    private val wallet = HdWallet(PairFixtures.seed)
    private val from = AccountAddresses.addressAt(wallet.accountXpub(), 0)

    // Settings

    @Test
    fun evmSettingsWithACustomChain() = compose.snapshot("evm_settings_custom_chain") {
        LenslyScreen(title = "SETTINGS", onBack = {}) {
            EvmSettingsSection(
                settings = EvmSettings(
                    addressCount = 3,
                    enabled = setOf(1L, 11155111L, 424242L),
                    rpc = mapOf(1L to "https://rpc.example.org"),
                    custom = listOf(CustomChain(424242L, "Demo Chain", "DEMO", testnet = false)),
                ),
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
        }
    }

    @Test
    fun evmAddCustomChainDialog() = dialogSnapshot(
        name = "evm_add_custom_chain_dialog",
        title = "Add a chain",
        open = { onNodeWithText("Add custom chain").performClick() },
    ) {
        LenslyScreen(title = "SETTINGS", onBack = {}) {
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
        }
    }

    @Test
    fun evmEditRpcDialog() = dialogSnapshot(
        name = "evm_edit_rpc_dialog",
        title = "RPC for Ethereum",
        open = { onAllNodesWithText("Edit RPC").onFirst().performClick() },
    ) {
        LenslyScreen(title = "SETTINGS", onBack = {}) {
            EvmSettingsSection(
                settings = EvmSettings(rpc = mapOf(1L to "https://rpc.example.org")),
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
        }
    }

    @Test
    fun dataProvidersWithEveryStatus() = compose.snapshot("data_providers_statuses") {
        DataProvidersScreen(
            data = listOf(
                providerRow("etherscan", acceptsKey = true, status = ProviderStatus.NeedsKey, canMoveUp = false),
                providerRow("blockscout", acceptsKey = false, status = ProviderStatus.NotActive),
                providerRow("blockchair", acceptsKey = false, status = ProviderStatus.CircuitOpen(retryInMs = 90_000L)),
                providerRow("routescan", acceptsKey = false, status = ProviderStatus.CoolingDown(inMs = 30_000L)),
                providerRow("mempool", acceptsKey = false, status = ProviderStatus.Healthy(120.0)),
                providerRow("3xpl", acceptsKey = false, status = ProviderStatus.Ready, canMoveDown = false, enabled = false),
            ),
            rpc = listOf(
                providerRow("1rpc", acceptsKey = true, mev = true, status = ProviderStatus.Off, enabled = false, apiKey = "k-1"),
            ),
            onBack = {},
            onEnabled = { _, _ -> },
            onMove = { _, _ -> },
            onApiKey = { _, _ -> },
        )
    }

    // Licences

    @Test
    fun licences() = compose.snapshot("licences") {
        ThirdPartyLicencesScreen(
            blocks = listOf(
                NoticeBlock("Third-party licences", heading = true),
                NoticeBlock("Kotlin, Jetpack Compose and Roborazzi are licensed under the Apache License, Version 2.0.", heading = false),
                NoticeBlock("The Bitcoin and Ethereum libraries are licensed under the MIT License.", heading = false),
            ),
            onBack = {},
        )
    }

    @Test
    fun licencesLoading() = compose.snapshot("licences_loading") {
        ThirdPartyLicencesScreen(blocks = null, onBack = {})
    }

    // Network controls

    @Test
    fun networkSwitchOnMainnet() = compose.snapshot("network_switch_mainnet") {
        Column(Modifier.padding(LenslySpacing.md)) {
            NetworkSwitch(mode = NetworkMode.MAINNET, onChange = {})
        }
    }

    @Test
    fun networkSwitchOnTestnetWithBanner() = compose.snapshot("network_switch_testnet_banner") {
        Column {
            TestnetBanner()
            Column(Modifier.padding(LenslySpacing.md)) {
                NetworkSwitch(mode = NetworkMode.TESTNET, onChange = {})
            }
        }
    }

    // Bitcoin send: review, sign request, and the broadcast states

    private val btcAccount = BtcAccount.fromSeed(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
    private val btcPlan: BtcSendPlan by lazy {
        val coin = BtcCoin(BtcFixtures.funding(btcAccount, 0, 0, 100_000L), 0, 0, 0)
        val recipient = BtcAccount.fromSeed(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)
        BtcSend.plan(btcAccount.watchOnly(), BtcSpendable(listOf(coin), nextChangeIndex = 0), recipient, 30_000L, 2.0)
    }

    @Test
    fun bitcoinSendReview() = compose.snapshot("bitcoin_send_review") {
        BtcSendReviewScreen(plan = btcPlan, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun bitcoinSendReviewRequestFailed() = compose.snapshot("bitcoin_send_review_error") {
        BtcSendReviewScreen(plan = btcPlan, notice = REQUEST_FAILED, onConfirm = {}, onBack = {})
    }

    @Test
    fun bitcoinSignRequest() = compose.snapshot("bitcoin_send_sign_request") {
        BtcSignRequestScreen(network = BtcNetwork.MAINNET, parts = btcPlan.parts(), notice = null, onScan = {}, onBack = {})
    }

    @Test
    fun bitcoinBroadcastIdle() = compose.snapshot("bitcoin_broadcast_idle") {
        SignatureScanScreen(progress = null, status = SCAN_HINT, onText = {}, onCancel = {})
    }

    @Test
    fun bitcoinBroadcastSending() = compose.snapshot("bitcoin_broadcast_sending") {
        SignatureScanScreen(progress = 1f, status = SCAN_SENDING, onText = {}, onCancel = {})
    }

    @Test
    fun bitcoinBroadcastSuccess() = compose.snapshot("bitcoin_broadcast_success") {
        BtcSendSentScreen(network = BtcNetwork.MAINNET, txid = TXID, notice = null, onOpenExplorer = {}, onDone = {})
    }

    @Test
    fun bitcoinBroadcastSuccessWithoutBrowser() = compose.snapshot("bitcoin_broadcast_success_no_browser") {
        BtcSendSentScreen(network = BtcNetwork.MAINNET, txid = TXID, notice = NO_BROWSER, onOpenExplorer = {}, onDone = {})
    }

    @Test
    fun bitcoinBroadcastError() = compose.snapshot("bitcoin_broadcast_error") {
        BtcSignRequestScreen(network = BtcNetwork.MAINNET, parts = btcPlan.parts(), notice = BROADCAST_FAILED, onScan = {}, onBack = {})
    }

    // Ethereum send: review, sign request, and the broadcast states

    private val ethSend: PreparedSend = PreparedSend(
        chain = ethereum,
        index = 0,
        from = from,
        to = RECIPIENT,
        valueWei = BigInteger.TEN.pow(16),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = BigInteger.TEN.pow(18),
    )

    private val ethParts: List<String> by lazy {
        partsOf(EthSignRequestEncoder.encode(EvmSend.request(ethSend, PAIR_FINGERPRINT, REQUEST_ID)))
    }

    @Test
    fun ethereumSendReview() = compose.snapshot("ethereum_send_review") {
        SendReviewScreen(send = ethSend, notice = null, onConfirm = {}, onBack = {})
    }

    @Test
    fun ethereumSendReviewRequestFailed() = compose.snapshot("ethereum_send_review_error") {
        SendReviewScreen(send = ethSend, notice = REQUEST_FAILED, onConfirm = {}, onBack = {})
    }

    @Test
    fun ethereumSignRequest() = compose.snapshot("ethereum_send_sign_request") {
        SignRequestScreen(send = ethSend, parts = ethParts, notice = null, onScan = {}, onBack = {})
    }

    @Test
    fun ethereumBroadcastIdle() = compose.snapshot("ethereum_broadcast_idle") {
        SignatureScanScreen(progress = null, status = SCAN_HINT, onText = {}, onCancel = {})
    }

    @Test
    fun ethereumBroadcastSending() = compose.snapshot("ethereum_broadcast_sending") {
        SignatureScanScreen(progress = 1f, status = SCAN_SENDING, onText = {}, onCancel = {})
    }

    @Test
    fun ethereumBroadcastSuccess() = compose.snapshot("ethereum_broadcast_success") {
        SendSentScreen(chain = ethereum, hash = TXHASH, explorerUrl = "https://etherscan.io/tx/$TXHASH", notice = null, onOpenExplorer = {}, onDone = {})
    }

    @Test
    fun ethereumBroadcastSuccessOnAChainWithoutExplorer() = compose.snapshot("ethereum_broadcast_success_no_explorer") {
        SendSentScreen(chain = sepolia, hash = TXHASH, explorerUrl = null, notice = null, onOpenExplorer = {}, onDone = {})
    }

    @Test
    fun ethereumBroadcastError() = compose.snapshot("ethereum_broadcast_error") {
        SignRequestScreen(send = ethSend, parts = ethParts, notice = BROADCAST_FAILED, onScan = {}, onBack = {})
    }

    // Pairing: the scan, and the import result

    @Test
    fun pairScanIdle() = compose.snapshot("pair_scan_idle") {
        PairScanScreen(progress = null, status = PAIR_SCAN_HINT, onText = {}, onCancel = {})
    }

    @Test
    fun pairScanReadingAnAnimatedCode() = compose.snapshot("pair_scan_reading") {
        PairScanScreen(progress = 0.4f, status = PAIR_SCAN_READING, onText = {}, onCancel = {})
    }

    @Test
    fun pairReviewWhenAlreadyPaired() = compose.snapshot("pair_review_already_paired") {
        PairReviewScreen(pairing = pairing(withBitcoin = false), alreadyPaired = true, error = null, onSave = {}, onCancel = {})
    }

    @Test
    fun pairReviewWhenSaveFails() = compose.snapshot("pair_review_save_failed") {
        PairReviewScreen(pairing = pairing(withBitcoin = false), alreadyPaired = false, error = PAIR_SAVE_FAILED, onSave = {}, onCancel = {})
    }

    @Test
    fun pairReviewWithBitcoinAccounts() = compose.snapshot("pair_review_bitcoin") {
        PairReviewScreen(pairing = pairing(withBitcoin = true), alreadyPaired = false, error = null, onSave = {}, onCancel = {})
    }

    private fun pairing(withBitcoin: Boolean): PairingPayload {
        val apps = mutableListOf(
            PairingApp(
                app = PairImport.ETHEREUM,
                keys = listOf(PairingKey.ethereumAccount(wallet.accountXpub())),
                accounts = listOf(PairingAccount(key = 0, child = 0, name = null, label = null, group = null)),
            ),
        )
        if (withBitcoin) {
            val account = BtcWatchOnly.accounts(PairFixtures.seed, BtcNetwork.MAINNET).first { it.scriptType == ScriptType.NATIVE_SEGWIT }
            apps += PairingApp(
                app = PairImport.BITCOIN,
                keys = listOf(PairingKey.bitcoinAccount(account)),
                accounts = listOf(PairingAccount(key = 0, child = null, name = null, label = null, group = null)),
            )
        }
        return PairingPayload(fingerprint = 0x1a2b3c4dL, name = "Demo", apps = apps)
    }

    /** The provider row of a fake provider. [enabled] is the owner's switch, and the other fields are as in [LumioScreenshotTest]. */
    private fun providerRow(
        id: String,
        acceptsKey: Boolean,
        status: ProviderStatus,
        mev: Boolean = false,
        enabled: Boolean = true,
        apiKey: String? = null,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
    ) = ProviderRow(
        id = id,
        mevProtected = mev,
        acceptsKey = acceptsKey,
        enabled = enabled,
        apiKey = apiKey,
        status = status,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )

    /**
     * Renders [content] in the same frame as [snapshot], runs [open] to show a dialog, and records the dialog window as [name].png. A dialog is
     * its own window, so [snapshot] would leave it out of the frame. The change threshold is the one [snapshot] uses.
     */
    private fun dialogSnapshot(
        name: String,
        title: String,
        open: ComposeContentTestRule.() -> Unit,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(PHONE_DENSITY)) {
                LenslyTheme {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = (PHONE_WIDTH_PX / PHONE_DENSITY).dp, height = (PHONE_HEIGHT_PX / PHONE_DENSITY).dp)
                            .background(LenslyBlack),
                    ) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.open()
        // A dialog attaches its window on a later frame, so the clock moves past it before the capture.
        compose.mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        compose.waitForIdle()
        // The dialog is a root of its own: the one whose descendants hold its title.
        compose.onAllNodes(isRoot()).filterToOne(hasAnyDescendant(hasText(title))).captureRoboImage(
            filePath = "$SCREENSHOT_DIR/$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD),
            ),
        )
    }

    private companion object {
        /** The owner's phone, as [snapshot] sizes it: 720 x 1600 px at density 1.75. */
        const val PHONE_WIDTH_PX = 720f
        const val PHONE_HEIGHT_PX = 1600f
        const val PHONE_DENSITY = 1.75f

        /** The share of pixels allowed to differ from a golden, as in the screenshot harness. */
        const val CHANGE_THRESHOLD = 0.0005f

        /** How far the clock moves so that a dialog window has attached. */
        const val DIALOG_SETTLE_MS = 1_000L

        /** A checksummed address, which the unsigned transaction needs. */
        const val RECIPIENT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        const val TXID = "3f5e0c9a7b1d2e4f6a8b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f"
        const val TXHASH = "0x9a8b7c6d5e4f30291817161514131211100f0e0d0c0b0a090807060504030201"

        /** The request ID of the fake sign request, sixteen bytes. */
        val REQUEST_ID = ByteArray(16) { it.toByte() }
        val PAIR_FINGERPRINT = 0x1a2b3c4dL

        // The words the send and pairing routes show. They are copied from SendRoutes, BtcSendRoutes and PairRoutes.
        const val SCAN_HINT = "Point the camera at the signature that Lensly shows."
        const val SCAN_SENDING = "Signature read. Sending the transaction to the network."
        const val REQUEST_FAILED = "The sign request could not be made. Try again."
        const val BROADCAST_FAILED = "The transaction could not be sent. Check the balance, then try again."
        const val NO_BROWSER = "No browser can open the explorer."
        const val PAIR_SCAN_HINT = "Point the camera at the pairing code that Lensly shows."
        const val PAIR_SCAN_READING = "Reading the code. Keep the camera on it."
        const val PAIR_SAVE_FAILED = "The pairing could not be saved. Try again."
    }
}
