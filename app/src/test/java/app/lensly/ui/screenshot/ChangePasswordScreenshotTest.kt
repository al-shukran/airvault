package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.R
import app.lensly.ui.gate.PolicyError
import app.lensly.ui.settings.ChangeCopy
import app.lensly.ui.settings.ChangePasswordScreen
import app.lensly.ui.settings.ChangePasswordState
import app.lensly.ui.settings.ChangeStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** App Password change: each step, a refused password, the rekey and the progress while it runs, and the finished change. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChangePasswordScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun currentStep() = compose.snapshot("change_password_current") {
        screen(ChangePasswordState(step = ChangeStep.Current, length = 6))
    }

    @Test
    fun checkingCurrent() = compose.snapshot("change_password_checking") {
        screen(ChangePasswordState(step = ChangeStep.Current, busy = true))
    }

    @Test
    fun newPasswordRefused() = compose.snapshot("change_password_refused") {
        screen(ChangePasswordState(step = ChangeStep.New, length = 4, error = PolicyError.TooShort))
    }

    @Test
    fun confirmMismatch() = compose.snapshot("change_password_mismatch") {
        screen(ChangePasswordState(step = ChangeStep.Confirm, length = 8, error = PolicyError.Mismatch))
    }

    @Test
    fun changing() = compose.snapshot("change_password_changing") {
        screen(ChangePasswordState(step = ChangeStep.Confirm, busy = true))
    }

    @Test
    fun changed() = compose.snapshot("change_password_changed") {
        screen(ChangePasswordState(changed = true))
    }

    /** The Duress Password set: the warning that using it erases the vault shows under the hint, on every step. */
    @Test
    fun duressNew() = compose.snapshot("duress_password_new") {
        screen(ChangePasswordState(step = ChangeStep.New, length = 6), duress = true)
    }

    @Test
    fun duressCurrent() = compose.snapshot("duress_password_current") {
        screen(ChangePasswordState(step = ChangeStep.Current), duress = true)
    }
}

@Composable
private fun screen(state: ChangePasswordState, duress: Boolean = false) {
    ChangePasswordScreen(
        copy = if (duress) {
            ChangeCopy.DURESS_PASSWORD.copy(warning = stringResource(R.string.duress_password_warning))
        } else {
            ChangeCopy.APP_PASSWORD
        },
        state = state,
        onKey = {},
        onBackspace = {},
        onSubmit = {},
        onBack = {},
        onDone = {},
    )
}
