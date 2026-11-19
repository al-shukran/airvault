package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.FailureCapScreen
import app.lensly.ui.settings.FailureCapStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Failure Delay cap: the choice with the cap in force, and the App Password step, by day and by night. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FailureCapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choose() = compose.snapshot("failure_cap_choose") { ChooseSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun chooseNight() = compose.snapshot("failure_cap_choose_night") { ChooseSnapshot() }

    @Test
    fun password() = compose.snapshot("failure_cap_password") { PasswordSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun passwordNight() = compose.snapshot("failure_cap_password_night") { PasswordSnapshot() }
}

@Composable
private fun ChooseSnapshot() {
    FailureCapSnapshot(step = FailureCapStep.Choose, saved = FailureCapChoice.DEFAULT, selected = null)
}

@Composable
private fun PasswordSnapshot() {
    FailureCapSnapshot(
        step = FailureCapStep.Password(FailureCapChoice.FIVE_MINUTES, eraseAfter = 10, busy = false),
        saved = FailureCapChoice.DEFAULT,
        selected = FailureCapChoice.FIVE_MINUTES,
    )
}

@Composable
private fun FailureCapSnapshot(step: FailureCapStep, saved: FailureCapChoice?, selected: FailureCapChoice?) {
    FailureCapScreen(
        step = step,
        saved = saved,
        selected = selected,
        savedErase = 10,
        selectedErase = null,
        passwordLength = 0,
        onSelect = {},
        onSelectErase = {},
        onContinue = {},
        onKey = {},
        onBackspace = {},
        onSubmit = {},
        onBack = {},
    )
}
