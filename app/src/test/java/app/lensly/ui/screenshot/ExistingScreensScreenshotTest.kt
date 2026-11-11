package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.gate.CreatePasswordScreen
import app.lensly.ui.gate.CreatePasswordState
import app.lensly.ui.gate.UnavailableScreen
import app.lensly.ui.gate.UnlockScreen
import app.lensly.ui.wallet.WalletStartScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Smoke test: four existing screens render at the owner's phone size. Record with :app:recordRoborazziDebug. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExistingScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unlockScreen() = compose.snapshot("unlock_screen") {
        UnlockScreen(length = 4, busy = false, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun createPasswordScreen() = compose.snapshot("create_password_screen") {
        CreatePasswordScreen(
            state = CreatePasswordState(length = 6),
            busy = false,
            onKey = {},
            onBackspace = {},
            onSubmit = {},
        )
    }

    @Test
    fun walletStartScreen() = compose.snapshot("wallet_start_screen") {
        WalletStartScreen(onCreate = {}, onRestore = {}, onImportKey = {})
    }

    @Test
    fun unavailableScreen() = compose.snapshot("unavailable_screen") {
        UnavailableScreen(onClose = {})
    }
}
