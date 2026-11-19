package app.lensly.ui.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import app.lensly.app.export.AccountExportView
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val APP_PASSWORD = "sesame"
private const val PASSWORD_PROMPT = "Application Password"
private const val QR_CODE = "QR code"

/**
 * The account export is behind the App Password, as the pairing code is. Nothing is loaded or composed for a wrong password, the code
 * appears once the right one is typed, and the export locks again when the app leaves the foreground.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class AccountExportPasswordTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theExportIsLoadedOnlyOnceTheAppPasswordIsRightAndLocksWhenTheAppStops() {
        var loads = 0
        val account = AccountExportView(
            address = "0xA1",
            derivationPath = "m/44'/60'/0'/0/0",
            parts = listOf("ur:eth-account/1-1/aa"),
            fields = emptyList(),
        )
        compose.setContent {
            LumenTheme {
                AccountExportGate(
                    count = 1,
                    checkPassword = { password -> if (String(password) == APP_PASSWORD) PasswordCheck.Accepted else PasswordCheck.Rejected },
                    load = {
                        loads++
                        listOf(account)
                    },
                    onUnavailable = {},
                    onDone = {},
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        assertEquals(0, loads, "the export is loaded only after the password")

        type("wrong")
        pressDone()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        assertEquals(0, loads, "a wrong password loads nothing")

        type(APP_PASSWORD)
        pressDone()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription(QR_CODE, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, loads)
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertDoesNotExist()

        stopApp()
        startApp()

        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
    }

    /** Taps each character of [text] on the in-app keyboard. */
    private fun type(text: String) {
        text.forEach { compose.onNodeWithContentDescription(it.toString()).performClick() }
    }

    private fun pressDone() {
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitForIdle()
    }

    /** Moves the activity to the background, as a stop does when the owner leaves the app. */
    private fun stopApp() = moveActivity(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)

    /** Brings the activity back to the foreground. */
    private fun startApp() = moveActivity(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)

    private fun moveActivity(vararg events: Lifecycle.Event) {
        compose.runOnIdle {
            val lifecycle = compose.activity.lifecycle as LifecycleRegistry
            events.forEach { lifecycle.handleLifecycleEvent(it) }
        }
        compose.waitForIdle()
    }
}
