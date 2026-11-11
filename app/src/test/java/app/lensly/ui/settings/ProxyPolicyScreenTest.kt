package app.lensly.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.registry.ProxyPolicy
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The proxy policy choice: each option in plain words, Continue only for a change, and the App Password step after it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ProxyPolicyScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(saved: ProxyPolicy?, selected: ProxyPolicy?, onContinue: () -> Unit = {}) {
        compose.setContent {
            LenslyTheme {
                ProxyPolicyScreen(
                    step = ProxyPolicyStep.Choose,
                    saved = saved,
                    selected = selected,
                    passwordLength = 0,
                    onSelect = {},
                    onContinue = onContinue,
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun eachPolicyIsNamedAndExplainedInPlainWords() {
        show(saved = ProxyPolicy.DEFAULT, selected = null)

        compose.onNodeWithText("Bind to implementation").assertExists()
        compose.onNodeWithText(ProxyPolicy.BIND_TO_IMPLEMENTATION.explanation).assertExists()
        compose.onNodeWithText("Refuse all proxies").assertExists()
        compose.onNodeWithText(ProxyPolicy.REFUSE_ALL_PROXIES.explanation).assertExists()
        compose.onNodeWithText("Warn and confirm").assertExists()
        compose.onNodeWithText(ProxyPolicy.WARN_AND_CONFIRM.explanation).assertExists()
    }

    @Test
    fun continueIsOffUntilThePickDiffersFromTheSavedPolicy() {
        show(saved = ProxyPolicy.DEFAULT, selected = null)

        compose.onNodeWithText("CONTINUE").assertIsNotEnabled()
    }

    @Test
    fun choosingAnotherPolicyEnablesContinueAndContinueOpensThePasswordStep() {
        var continued = false
        compose.setContent {
            LenslyTheme {
                var selected by remember { mutableStateOf<ProxyPolicy?>(null) }
                ProxyPolicyScreen(
                    step = ProxyPolicyStep.Choose,
                    saved = ProxyPolicy.DEFAULT,
                    selected = selected,
                    passwordLength = 0,
                    onSelect = { selected = it },
                    onContinue = { continued = true },
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Warn and confirm").performClick()
        compose.onNodeWithText("CONTINUE").assertIsEnabled().performClick()

        assertTrue(continued)
    }

    @Test
    fun thePasswordStepAsksForTheAppPasswordBeforeSaving() {
        compose.setContent {
            LenslyTheme {
                ProxyPolicyScreen(
                    step = ProxyPolicyStep.Password(ProxyPolicy.REFUSE_ALL_PROXIES, busy = false),
                    saved = ProxyPolicy.DEFAULT,
                    selected = ProxyPolicy.REFUSE_ALL_PROXIES,
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
        }

        compose.onNodeWithText("Enter your App Password to save this policy.").assertExists()
    }
}
