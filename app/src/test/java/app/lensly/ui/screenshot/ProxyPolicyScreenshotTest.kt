package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.registry.ProxyPolicy
import app.lensly.ui.settings.ProxyPolicyScreen
import app.lensly.ui.settings.ProxyPolicyStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Proxy policy: the choice with each policy explained and one picked, and the App Password step that saves it, by day and by night. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProxyPolicyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun choose() = compose.snapshot("proxy_policy_choose") { ChooseSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun chooseNight() = compose.snapshot("proxy_policy_choose_night") { ChooseSnapshot() }

    @Test
    fun password() = compose.snapshot("proxy_policy_password") { PasswordSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun passwordNight() = compose.snapshot("proxy_policy_password_night") { PasswordSnapshot() }
}

@Composable
private fun ChooseSnapshot() {
    ProxyPolicyScreen(
        step = ProxyPolicyStep.Choose,
        saved = ProxyPolicy.DEFAULT,
        selected = ProxyPolicy.WARN_AND_CONFIRM,
        passwordLength = 0,
        onSelect = {},
        onContinue = {},
        onKey = {},
        onBackspace = {},
        onSubmit = {},
        onBack = {},
    )
}

@Composable
private fun PasswordSnapshot() {
    ProxyPolicyScreen(
        step = ProxyPolicyStep.Password(ProxyPolicy.WARN_AND_CONFIRM, busy = false),
        saved = ProxyPolicy.DEFAULT,
        selected = ProxyPolicy.WARN_AND_CONFIRM,
        passwordLength = 0,
        onSelect = {},
        onContinue = {},
        onKey = {},
        onBackspace = {},
        onSubmit = {},
        onBack = {},
    )
}
