package app.lensly.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.CoverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The progress row of the Confirm step names the change that is running: the Duress step must not say it changes the Application Password. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ChangePasswordLabelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showBusyConfirm(copy: ChangeCopy) {
        compose.setContent {
            CoverTheme {
                ChangePasswordScreen(
                    state = ChangePasswordState(step = ChangeStep.Confirm, busy = true),
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                    onDone = {},
                    copy = copy,
                )
            }
        }
    }

    @Test
    fun theDuressStepSaysItIsSettingTheDuressPassword() {
        showBusyConfirm(ChangeCopy.DURESS_PASSWORD)

        compose.onNodeWithText("Setting the Duress Password", ignoreCase = true).assertExists()
        compose.onNodeWithText("Changing the Application Password", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun theAppPasswordStepSaysItIsChangingTheApplicationPassword() {
        showBusyConfirm(ChangeCopy.APP_PASSWORD)

        compose.onNodeWithText("Changing the Application Password", ignoreCase = true).assertExists()
    }
}
