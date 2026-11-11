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

/** Failure Delay cap: the choice with the cap in force, the warning under a cap below 10 s, and the App Password step. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FailureCapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choose() = compose.snapshot("failure_cap_choose") {
        FailureCapSnapshot(step = FailureCapStep.Choose, saved = FailureCapChoice.DEFAULT, selected = null)
    }

    @Test
    fun chooseBelowTenSeconds() = compose.snapshot("failure_cap_choose_warning") {
        FailureCapSnapshot(step = FailureCapStep.Choose, saved = FailureCapChoice.DEFAULT, selected = FailureCapChoice.ONE_SECOND)
    }

    @Test
    fun password() = compose.snapshot("failure_cap_password") {
        FailureCapSnapshot(
            step = FailureCapStep.Password(FailureCapChoice.FIVE_MINUTES, busy = false),
            saved = FailureCapChoice.DEFAULT,
            selected = FailureCapChoice.FIVE_MINUTES,
        )
    }
}

@Composable
private fun FailureCapSnapshot(step: FailureCapStep, saved: FailureCapChoice?, selected: FailureCapChoice?) {
    FailureCapScreen(
        step = step,
        saved = saved,
        selected = selected,
        passwordLength = 0,
        onSelect = {},
        onContinue = {},
        onKey = {},
        onBackspace = {},
        onSubmit = {},
        onBack = {},
        onLock = {},
    )
}
