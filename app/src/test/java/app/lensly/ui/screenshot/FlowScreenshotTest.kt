package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.export.AccountExportView
import app.lensly.core.registry.WalletEntry
import app.lensly.ui.flow.AccountExportScreen
import app.lensly.ui.flow.ConfirmPasswordScreen
import app.lensly.ui.flow.PairFormat
import app.lensly.ui.flow.PairLoading
import app.lensly.ui.flow.PairStep
import app.lensly.ui.flow.PairWizardScreen
import app.lensly.ui.shippedRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sign confirmation, the pairing wizard's four steps and its single-key notice, and the account export. The password screen never shows an error, and the test keeps that rule. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlowScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmPassword() = compose.snapshot("confirm_password") {
        ConfirmPasswordScreen(length = 6, busy = false, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun confirmPasswordChecking() = compose.snapshot("confirm_password_checking") {
        ConfirmPasswordScreen(length = 6, busy = true, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun pairWizardChoosesAWallet() = compose.snapshot("pair_wizard_step1_wallets") {
        pairWizard(step = PairStep.WALLET)
    }

    @Test
    fun pairWizardChoosesAnApp() = compose.snapshot("pair_wizard_step2_apps") {
        pairWizard(step = PairStep.APP)
    }

    @Test
    fun pairWizardExplainsASingleKey() = compose.snapshot("pair_wizard_step2_single_key") {
        pairWizard(step = PairStep.APP, wallet = wallets.last())
    }

    @Test
    fun pairWizardShowsTheAppSteps() = compose.snapshot("pair_wizard_step3_instructions") {
        pairWizard(step = PairStep.INSTRUCTIONS, format = PairFormat.RABBY)
    }

    @Test
    fun pairWizardShowsTheWalletEntry() = compose.snapshot("pair_wizard_step3_wallet_entry") {
        pairWizard(
            step = PairStep.INSTRUCTIONS,
            format = PairFormat.RABBY,
            walletEntry = shippedRegistry().walletEntries().single { it.name == "Rabby" },
        )
    }

    @Test
    fun pairWizardShowsTheCode() = compose.snapshot("pair_wizard_step4_code") {
        pairWizard(step = PairStep.CODE, format = PairFormat.RABBY, frames = listOf(SAMPLE_UR))
    }

    @Test
    fun pairLoading() = compose.snapshot("pair_wallet_loading") {
        PairLoading(label = "Preparing code", onBack = {})
    }

    @Test
    fun accountExport() = compose.snapshot("account_export_screen") {
        // A fixed code and fields, as the pair wizard's screenshot uses SAMPLE_UR. The live encoder is covered by unit tests.
        AccountExportScreen(
            accounts = listOf(
                AccountExportView(
                    address = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
                    derivationPath = "m/44'/60'/0'/0/0",
                    parts = listOf(SAMPLE_UR),
                    fields = listOf(
                        "Derivation path" to "m/44'/60'/0'/0/0",
                        "Depth" to "5",
                        "Public key" to "02eae4b876a8696134b868f88cc2f51f715f2dbedb7446b8e6edf3d4541c4eb67b",
                        "Chain code" to "d882718b7a42806803eeb17f7483f20620611adb88fc943c898dc5aba94c2819",
                        "Parent fingerprint" to "d32e4508",
                        "Master fingerprint" to "e9181cf3",
                    ),
                ),
            ),
            onDone = {},
            onBack = {},
        )
    }

    @Test
    fun accountExportSingleKey() = compose.snapshot("account_export_single_key") {
        AccountExportScreen(
            accounts = listOf(
                AccountExportView(
                    address = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23",
                    derivationPath = null,
                    parts = listOf("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"),
                    fields = emptyList(),
                ),
            ),
            onDone = {},
            onBack = {},
        )
    }

    @Composable
    private fun pairWizard(
        step: PairStep,
        wallet: WalletMeta? = wallets.first(),
        format: PairFormat? = null,
        walletEntry: WalletEntry? = null,
        frames: List<String>? = null,
    ) {
        PairWizardScreen(
            step = step,
            wallets = wallets,
            profiles = emptyMap(),
            wallet = wallet,
            format = format,
            walletEntry = walletEntry,
            frames = frames,
            onPickWallet = {},
            onPickFormat = {},
            onShowCode = {},
            onDone = {},
            onBack = {},
        )
    }

    /** Two Mnemonic wallets and a Single Key. The picker lists all three, and the single-key notice uses the last. */
    private val wallets = listOf(
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
            name = "Savings",
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
}
