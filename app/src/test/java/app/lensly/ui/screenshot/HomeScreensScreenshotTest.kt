package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.AccountProfile
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.home.BulkGenerateScreen
import app.lensly.ui.home.ImportState
import app.lensly.ui.home.RegistryImportScreen
import app.lensly.ui.home.SoonScreen
import app.lensly.ui.home.WalletDetailScreen
import app.lensly.ui.home.WalletHomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home screens, one snapshot per state: the wallet list (empty, with wallets, with the debug notice), wallet detail
 * (deriving, accounts, Single Key), bulk generate (empty, ready, refused, running, failed), catalog import (idle,
 * checking, installed, refused), and the placeholder. Record with :app:recordRoborazziDebug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun walletListEmpty() = compose.snapshot("wallet_list_empty") {
        list(wallets = emptyList())
    }

    @Test
    fun walletListWithWallets() = compose.snapshot("wallet_list_with_wallets") {
        list(wallets = sampleWallets())
    }

    @Test
    fun walletListWithDebugNotice() = compose.snapshot("wallet_list_debug_notice") {
        list(wallets = sampleWallets().take(1), debugNotice = "Keystore is software-only on this device.")
    }

    @Test
    fun walletDetailWhileAddressesDerive() = compose.snapshot("wallet_detail_deriving") {
        detail(wallet = sampleWallets()[0], addresses = null)
    }

    @Test
    fun walletDetailWithAccounts() = compose.snapshot("wallet_detail_accounts") {
        detail(
            wallet = sampleWallets()[0],
            addresses = sampleAddresses,
            profiles = sampleProfiles,
            walletProfile = WalletProfile(
                colour = ProfileColour.ORANGE,
                icon = ProfileIcon.HOME,
                label = "Everyday",
                groups = listOf("Work"),
            ),
        )
    }

    @Test
    fun walletDetailSingleKey() = compose.snapshot("wallet_detail_single_key") {
        detail(wallet = sampleWallets()[2], addresses = sampleAddresses.take(1))
    }

    @Test
    fun bulkGenerateWithoutACount() = compose.snapshot("bulk_generate_empty") {
        bulk(digits = "")
    }

    @Test
    fun bulkGenerateReady() = compose.snapshot("bulk_generate_ready") {
        bulk(digits = "20")
    }

    @Test
    fun bulkGenerateRefusesAboveTheCap() = compose.snapshot("bulk_generate_refused") {
        bulk(digits = "5000")
    }

    @Test
    fun bulkGenerateWhileRunning() = compose.snapshot("bulk_generate_running") {
        bulk(digits = "50", running = true, derived = 18)
    }

    @Test
    fun bulkGenerateAfterAFailedRun() = compose.snapshot("bulk_generate_failed") {
        bulk(digits = "20", failure = "Run failed. Nothing was saved")
    }

    @Test
    fun registryImportIdle() = compose.snapshot("registry_import_idle") {
        registry(ImportState.Idle)
    }

    @Test
    fun registryImportChecking() = compose.snapshot("registry_import_checking") {
        registry(ImportState.Checking)
    }

    @Test
    fun registryImportInstalled() = compose.snapshot("registry_import_installed") {
        registry(
            ImportState.Installed(
                sequence = 7,
                networks = listOf(linea),
                tokens = listOf(usdc),
                chains = listOf(linea),
            ),
        )
    }

    @Test
    fun registryImportRefused() = compose.snapshot("registry_import_refused") {
        registry(
            ImportState.Refused(
                "Signature check failed. The file was altered, or it was not signed with the release key.",
            ),
        )
    }

    @Test
    fun soonPlaceholder() = compose.snapshot("soon_placeholder") {
        SoonScreen(onBack = {})
    }

    @Composable
    private fun list(wallets: List<WalletMeta>, debugNotice: String? = null) {
        WalletHomeScreen(
            wallets = wallets,
            onOpen = {},
            onAdd = {},
            onSettings = {},
            onLock = {},
            onUpdateCatalog = {},
            debugNotice = debugNotice,
        )
    }

    @Composable
    private fun detail(
        wallet: WalletMeta,
        addresses: List<String>?,
        profiles: Map<Int, AccountProfile> = emptyMap(),
        walletProfile: WalletProfile = WalletProfile(),
    ) {
        WalletDetailScreen(
            wallet = wallet,
            addresses = addresses,
            accountProfiles = profiles,
            walletProfile = walletProfile,
            walletPosition = 0,
            onBack = {},
            onLock = {},
            onAddAccount = {},
            onBulkGenerate = {},
            onPair = {},
            onSign = {},
            onShowSecret = {},
            onEditWallet = {},
            onEditAccount = {},
            onExportAccount = {},
            onRenameAccounts = {},
            onStyleAccounts = {},
            onResetAccountNames = {},
            onDeleteWallet = {},
        )
    }

    @Composable
    private fun bulk(
        digits: String,
        running: Boolean = false,
        derived: Int = 0,
        failure: String? = null,
    ) {
        BulkGenerateScreen(
            existingAccounts = 3,
            autoNameBase = "Seed wallet",
            cap = BulkCap.HUNDRED,
            digits = digits,
            running = running,
            derived = derived,
            failure = failure,
            onDigit = {},
            onBackspace = {},
            onPreset = {},
            onGenerate = {},
            onBack = {},
            onLock = {},
        )
    }

    @Composable
    private fun registry(state: ImportState) {
        RegistryImportScreen(
            state = state,
            onChooseFile = {},
            onBack = {},
            onLock = {},
        )
    }

    private fun sampleWallets(): List<WalletMeta> = listOf(
        WalletMeta(
            id = "w1",
            name = "Seed wallet",
            createdAtMs = 0L,
            deviceGenerated = true,
            accountCount = 3,
            masterFingerprintHex = "1a2b3c4d",
            accountXpub = null,
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Seed wallet",
        ),
        WalletMeta(
            id = "w2",
            name = "Savings and everyday spending wallet",
            createdAtMs = 0L,
            deviceGenerated = false,
            accountCount = 12,
            masterFingerprintHex = "9f8e7d6c",
            accountXpub = null,
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Savings",
        ),
        WalletMeta(
            id = "w3",
            name = "Hardware key",
            createdAtMs = 0L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = null,
            accountXpub = null,
            kind = WalletKind.SINGLE_KEY,
            autoNameBase = "Hardware key",
        ),
    )

    /** Two accounts in the group "Home", one ungrouped, so the detail shows both kinds of section. */
    private val sampleProfiles = mapOf(
        0 to AccountProfile(name = "Main", colour = ProfileColour.BLUE, icon = ProfileIcon.STAR, label = "Everyday", group = "Home"),
        1 to AccountProfile(name = "Savings", colour = ProfileColour.GREEN, group = "Home"),
        2 to AccountProfile(colour = ProfileColour.PINK, label = "Rent"),
    )

    private val sampleAddresses = listOf(
        "0xF278A1B2C3D4E5F60718293A4B5C6D7E8F901CDB",
        "0x9A3B7C1D2E4F50617283940A1B2C3D4E5F60B7E2",
        "0x4C5D6E7F8091A2B3C4D5E6F708192A3B4C5D6E7F",
    )

    private val linea = NetworkEntry(59144L, "Linea", "ETH", 18, FeeModel.L2_ROLLUP, "Lineascan", false)
    private val usdc = TokenEntry(59144L, "0x176211869ca2b568f2a7d4ee941e073a821ee1ff", "USDC", "USD Coin", 6)
}
