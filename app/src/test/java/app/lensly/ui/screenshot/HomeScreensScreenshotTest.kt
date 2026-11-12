package app.lensly.ui.screenshot

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.lensly.app.AccountProfile
import app.lensly.app.ProfileColour
import app.lensly.app.ProfileIcon
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.home.BulkGenerateScreen
import app.lensly.ui.home.HOME_SCROLL_TAG
import app.lensly.ui.home.HomeReadiness
import app.lensly.ui.home.ImportState
import app.lensly.ui.home.RegistryImportScreen
import app.lensly.ui.home.SoonScreen
import app.lensly.ui.home.WalletDetailScreen
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.home.WalletsScreen
import app.lensly.ui.settings.FailureCapChoice
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Long enough for the Home sections to settle before the capture. */
private const val SETTLE_MS = 1_000L

/** The moment every Home golden is read against, so the relative times on the goldens never change. */
private const val FIXED_NOW_MS = 1_800_000_000_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/** The swipes that bring the Home content to its end, and the fling each one is given to settle. */
private const val SCROLL_SWIPES = 10
private const val FLING_MS = 1_000L

/**
 * The home screens, one snapshot per state: Home (no wallets, two, five, with the debug notice, with the signing chart empty, by status,
 * by network and over seven days, with a backup needed for one wallet and for several, and with the logo collage still because the
 * animations are off), the Wallets list (five),
 * wallet detail (deriving, accounts, adding an account, Single Key), bulk generate (empty, ready, refused, running, failed),
 * catalog import (idle, checking, installed, refused), and the placeholder. Record with :app:recordRoborazziDebug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val defaultZone: TimeZone = TimeZone.getDefault()

    @Before
    fun readTheChartInUtc() {
        // The chart's days are read in the device's zone, so the goldens read them in UTC and do not move with the machine.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreAnimators() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        TimeZone.setDefault(defaultZone)
    }

    @Test
    fun homeWithoutWallets() = compose.snapshot("home_no_wallets", settleMs = SETTLE_MS) {
        home(wallets = emptyList())
    }

    @Test
    fun homeWithTwoWallets() = compose.snapshot("home_two_wallets", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles)
    }

    @Test
    fun homeWithFiveWallets() = compose.snapshot("home_five_wallets", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets(), profiles = sampleHomeProfiles)
    }

    @Test
    fun homeWithDebugNotice() = compose.snapshot("home_debug_notice", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles, debugNotice = "Software keystore")
    }

    /** Home with no record yet: the chart shows its placeholder line, and the one wallet is backed up, so no panel shows. */
    @Test
    fun homeChartBeforeAnySignature() = compose.snapshot("home_chart_empty", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles, recent = emptyList())
    }

    /** The chart over the last thirty days, stacked by status. */
    @Test
    fun homeChartByStatus() = compose.snapshot("home_chart_status", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles, recent = chartActivity())
    }

    /** The chart stacked by network, once the App segment is chosen. */
    @Test
    fun homeChartByApp() = compose.snapshot(
        "home_chart_app",
        settleMs = SETTLE_MS,
        beforeCapture = { tap("APP") },
    ) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles, recent = chartActivity())
    }

    /** The chart over the last seven days, once that window is chosen. */
    @Test
    fun homeChartBySevenDays() = compose.snapshot(
        "home_chart_week",
        settleMs = SETTLE_MS,
        beforeCapture = { tap("7 DAYS") },
    ) {
        home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles, recent = chartActivity())
    }

    /** One wallet's seed is not backed up, so the backup panel sits under Scan to sign. */
    @Test
    fun homeBackupNeeded() = compose.snapshot("home_backup_needed", settleMs = SETTLE_MS) {
        val wallets = sampleWallets().take(2).mapIndexed { index, wallet -> if (index == 0) wallet.copy(seedBackedUp = false) else wallet }
        home(wallets = wallets, profiles = sampleHomeProfiles)
    }

    /** Several seeds are not backed up, so the panel counts them. */
    @Test
    fun homeBackupNeededForSeveralWallets() = compose.snapshot("home_backup_needed_many", settleMs = SETTLE_MS) {
        home(wallets = sampleWallets().map { it.copy(seedBackedUp = false) }, profiles = sampleHomeProfiles)
    }

    /**
     * Home scrolled to its end, so Readiness is in view above the dock. The lockout here is one second, so its tile has the amber dot.
     */
    @Test
    fun homeScrolledToReadiness() = compose.snapshot(
        "home_readiness",
        settleMs = SETTLE_MS,
        beforeCapture = { scrollHomeToEnd() },
    ) {
        home(
            wallets = sampleWallets().take(2),
            profiles = sampleHomeProfiles,
            readiness = sampleReadiness.copy(lockout = FailureCapChoice.ONE_SECOND),
        )
    }

    /** The logo collage with the system's animations off: its rows stay where they start, and the golden shows that frame. */
    @Test
    fun homeCollageWithAnimationsOff() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.snapshot("home_collage_still", settleMs = SETTLE_MS, beforeCapture = { scrollHomeToEnd() }) {
            home(wallets = sampleWallets().take(2), profiles = sampleHomeProfiles)
        }
    }

    @Test
    fun walletsListWithFiveWallets() = compose.snapshot("wallets_five_wallets", settleMs = SETTLE_MS) {
        WalletsScreen(
            wallets = sampleWallets(),
            profiles = sampleHomeProfiles,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onAdd = {},
            onBack = {},
        )
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
    fun walletDetailAddingAnAccount() = compose.snapshot("wallet_detail_adding") {
        detail(wallet = sampleWallets()[0].copy(accountCount = 4), addresses = sampleAddresses)
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
    private fun home(
        wallets: List<WalletMeta>,
        profiles: Map<String, WalletProfile> = emptyMap(),
        recent: List<WalletReviewRecord> = sampleActivity(),
        debugNotice: String? = null,
        readiness: HomeReadiness = sampleReadiness,
    ) {
        WalletHomeScreen(
            wallets = wallets,
            profiles = profiles,
            recent = recent,
            readiness = readiness,
            nowMs = FIXED_NOW_MS,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onCreate = {},
            onRestore = {},
            onScan = {},
            onActivity = {},
            onWallets = {},
            onSettings = {},
            onLock = {},
            onStartPairing = {},
            onNetworks = {},
            onTokens = {},
            onCatalog = {},
            onLockout = {},
            onBackup = {},
            debugNotice = debugNotice,
        )
    }

    /**
     * Records over the last thirty days, with all three outcomes and five networks, so both chart modes have something to stack. The
     * networks past the three busiest fall into Other.
     */
    private fun chartActivity(): List<WalletReviewRecord> {
        val networks = listOf(1L to "Ethereum", 143L to "Monad", 56L to "BNB Chain", 8453L to "Base", 42161L to "Arbitrum One")
        return (0 until 60).map { index ->
            val outcome = when {
                index % 9 == 0 -> ReviewOutcome.REFUSED
                index % 13 == 0 -> ReviewOutcome.CANCELLED
                else -> ReviewOutcome.SIGNED
            }
            val (chainId, network) = networks[index % networks.size]
            val atMs = FIXED_NOW_MS - (index * 7 % 30) * DAY_MS - (index % 20) * HOUR_MS
            WalletReviewRecord(
                walletId = if (index % 2 == 0) "w1" else "w2",
                record = sampleRecord(atMs, outcome, chainId = chainId, networkName = network),
            )
        }
    }

    /** The readiness as an installed catalog package reports it, so the version tile shows a sequence. */
    private val sampleReadiness = HomeReadiness(
        networkCount = 12,
        tokenCount = 48,
        catalogSequence = 3L,
        lockout = FailureCapChoice.DEFAULT,
    )

    /**
     * Three records of the session's wallets: a transfer, a refusal whose reason wraps onto a second line and ends with an ellipsis, and
     * a message. They are listed out of order, so the screen has to sort them.
     */
    private fun sampleActivity(): List<WalletReviewRecord> = listOf(
        WalletReviewRecord("w1", sampleRecord(FIXED_NOW_MS - 12 * MINUTE_MS, ReviewOutcome.SIGNED, function = "transfer", amount = "0.0100 MON")),
        WalletReviewRecord(
            "w2",
            sampleRecord(
                FIXED_NOW_MS - 3 * HOUR_MS,
                ReviewOutcome.REFUSED,
                refusal = "Chain 999999 is not in the registry, so this request cannot be checked before it is signed.",
            ),
        ),
        WalletReviewRecord("w1", sampleRecord(FIXED_NOW_MS - 26 * HOUR_MS, ReviewOutcome.SIGNED, kind = ReviewKind.MESSAGE)),
    ).reversed()

    private fun sampleRecord(
        atMs: Long,
        outcome: ReviewOutcome,
        kind: ReviewKind = ReviewKind.TRANSACTION,
        function: String? = null,
        amount: String? = null,
        refusal: String? = null,
        chainId: Long = 1L,
        networkName: String = "Ethereum",
    ): ReviewRecord = ReviewRecord(
        atMs = atMs,
        outcome = outcome,
        kind = kind,
        chainId = chainId,
        networkName = networkName,
        accountIndex = 0,
        address = "0x00000000000000000000000000000000000000AA",
        function = function,
        to = null,
        amount = amount,
        tokenContract = null,
        fee = emptyList(),
        params = emptyList(),
        origin = null,
        refusal = refusal,
        dataHash = "00",
    )

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
            onShowSecret = {},
            onHistory = {},
            onEditWallet = {},
            onEditAccount = {},
            onExportAccounts = {},
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
        WalletMeta(
            id = "w4",
            name = "Cold storage",
            createdAtMs = 0L,
            deviceGenerated = true,
            accountCount = 2,
            masterFingerprintHex = "5e6f7a8b",
            accountXpub = null,
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Cold storage",
        ),
        WalletMeta(
            id = "w5",
            name = "Trip",
            createdAtMs = 0L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = "c0ffee42",
            accountXpub = null,
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Trip",
        ),
    )

    /** Two wallets with their own look, so the art of a card shows a chosen colour as well as a generated one. */
    private val sampleHomeProfiles = mapOf(
        "w1" to WalletProfile(colour = ProfileColour.ORANGE, icon = ProfileIcon.HOME, label = "Everyday"),
        "w4" to WalletProfile(colour = ProfileColour.TEAL),
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

/**
 * Taps the node with [text] and lets the frame the tap changes draw. The clock does not run on its own in these tests, so without the
 * advance the capture would show the screen from before the tap.
 */
private fun ComposeContentTestRule.tap(text: String) {
    onNodeWithText(text).performClick()
    mainClock.advanceTimeBy(SETTLE_MS)
    waitForIdle()
}

/** Swipes the Home content up until it stops, so its last section sits above the dock. */
private fun ComposeContentTestRule.scrollHomeToEnd() {
    repeat(SCROLL_SWIPES) {
        onNodeWithTag(HOME_SCROLL_TAG).performTouchInput {
            swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 200)
        }
        mainClock.advanceTimeBy(FLING_MS)
        waitForIdle()
    }
}
