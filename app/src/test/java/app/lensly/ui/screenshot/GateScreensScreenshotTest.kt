package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.gate.CreatePasswordScreen
import app.lensly.ui.gate.CreatePasswordState
import app.lensly.ui.gate.CreateStep
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.gate.UnavailableScreen
import app.lensly.ui.gate.UnlockScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate screens, one snapshot per state: password creation (both steps, the hint, each rejection, and creating) and
 * unlock (idle and unlocking), and the unavailable screen. Record with :app:recordRoborazziDebug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GateScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createPasswordFirstStepEmpty() = compose.snapshot("create_password_enter_empty") {
        create(CreatePasswordState())
    }

    @Test
    fun createPasswordCountsDownToTheMinimum() = compose.snapshot("create_password_enter_hint") {
        create(CreatePasswordState(length = 5))
    }

    @Test
    fun createPasswordRefusesAShortPassword() = compose.snapshot("create_password_enter_too_short") {
        create(CreatePasswordState(error = PolicyError.TooShort))
    }

    @Test
    fun createPasswordRefusesAWeakPassword() = compose.snapshot("create_password_enter_too_weak") {
        create(CreatePasswordState(error = PolicyError.TooWeak))
    }

    @Test
    fun createPasswordConfirmStep() = compose.snapshot("create_password_confirm") {
        create(CreatePasswordState(step = CreateStep.Confirm, length = 6))
    }

    @Test
    fun createPasswordMismatch() = compose.snapshot("create_password_mismatch") {
        create(CreatePasswordState(step = CreateStep.Confirm, error = PolicyError.Mismatch))
    }

    @Test
    fun createPasswordCreatingVault() = compose.snapshot("create_password_creating") {
        create(CreatePasswordState(step = CreateStep.Confirm, length = 10), busy = true)
    }

    @Test
    fun unlockIdle() = compose.snapshot("unlock_idle") {
        UnlockScreen(length = 6, busy = false, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun unlockWhileKeyIsDerived() = compose.snapshot("unlock_unlocking") {
        UnlockScreen(length = 0, busy = true, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun unavailableVault() = compose.snapshot("unavailable_vault") {
        UnavailableScreen(onClose = {})
    }

    @Composable
    private fun create(state: CreatePasswordState, busy: Boolean = false) {
        CreatePasswordScreen(
            state = state,
            onKey = {},
            onBackspace = {},
            onSubmit = {},
            busy = busy,
        )
    }
}
