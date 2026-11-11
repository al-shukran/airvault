package app.lensly.ui.screenshot

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
}
