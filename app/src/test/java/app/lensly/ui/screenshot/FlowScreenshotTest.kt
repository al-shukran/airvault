package app.lensly.ui.screenshot

import app.lensly.ui.flow.AccountExportScreen
import app.lensly.ui.flow.ConfirmPasswordScreen
import app.lensly.ui.flow.PairChooserScreen
import app.lensly.ui.flow.PairLoading
import app.lensly.ui.flow.SingleKeyPairScreen
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sign confirmation and pairing states. The password screen never shows an error, and the test keeps that rule. */
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
    fun pairChooser() = compose.snapshot("pair_wallet_chooser") {
        PairChooserScreen(onPick = {}, onBack = {})
    }

    @Test
    fun pairSingleKey() = compose.snapshot("pair_wallet_single_key") {
        SingleKeyPairScreen(onBack = {})
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
}
