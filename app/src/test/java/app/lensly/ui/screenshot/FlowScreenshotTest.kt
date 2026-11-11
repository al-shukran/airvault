package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.flow.AccountExportScreen
import app.lensly.ui.flow.ConfirmPasswordScreen
import app.lensly.ui.flow.PairFormat
import app.lensly.ui.flow.PairLoading
import app.lensly.ui.flow.PairStep
import app.lensly.ui.flow.PairWizardScreen
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
    fun pairWizardShowsTheCode() = compose.snapshot("pair_wizard_step4_code") {
        pairWizard(step = PairStep.CODE, format = PairFormat.RABBY, frames = listOf(SAMPLE_UR))
    }

    @Test
    fun pairLoading() = compose.snapshot("pair_wallet_loading") {
        PairLoading(label = "Preparing code", onBack = {})
    }

    @Test
    fun accountExport() = compose.snapshot("account_export_screen") {
        AccountExportScreen(
            address = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
            derivationPath = "m/44'/60'/0'/0/0",
            onDone = {},
            onBack = {},
        )
    }

    @Test
    fun accountExportSingleKey() = compose.snapshot("account_export_single_key") {
        AccountExportScreen(
            address = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23",
            derivationPath = null,
            onDone = {},
            onBack = {},
        )
    }

    @Composable
    private fun pairWizard(
        step: PairStep,
        wallet: WalletMeta? = wallets.first(),
        format: PairFormat? = null,
        frames: List<String>? = null,
    ) {
        PairWizardScreen(
            step = step,
            wallets = wallets,
            profiles = emptyMap(),
            wallet = wallet,
            format = format,
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
