package app.lensly.ui.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.wallet.AccountXpub
import app.lensly.ui.theme.LumenTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val APP_PASSWORD = "sesame"
private const val PASSWORD_PROMPT = "Application Password"

/**
 * The AirVault code is built only once the App Password is right. A wrong password builds nothing, an unavailable vault leaves for its
 * notice, and the code locks again when the app leaves the foreground, as Show Secret does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class PairCodePasswordTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The wallets whose AirVault code was built, one entry for each build. */
    private val built = mutableListOf<WalletMeta>()
    private val wallet = mnemonic()

    @Test
    fun noCodeIsBuiltBeforeTheAppPasswordIsRight() {
        showLumioCode(check = { password -> if (String(password) == APP_PASSWORD) PasswordCheck.Accepted else PasswordCheck.Rejected })

        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        assertTrue(built.isEmpty(), "the code is built only after the password")

        type("wrong")
        pressDone()
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        assertTrue(built.isEmpty(), "a wrong password builds no code")

        type(APP_PASSWORD)
        pressDone()
        assertEquals(listOf(wallet), built)
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done", ignoreCase = true).assertExists()
    }

    @Test
    fun anUnavailableVaultLeavesForTheNoticeWithNoCode() {
        var unavailable = 0
        showLumioCode(check = { PasswordCheck.Unavailable }, onUnavailable = { unavailable++ })

        type(APP_PASSWORD)
        pressDone()

        assertEquals(1, unavailable)
        assertTrue(built.isEmpty())
    }

    @Test
    fun theCodeLocksAgainWhenTheAppLeavesTheForeground() {
        showLumioCode(check = { PasswordCheck.Accepted })
        type(APP_PASSWORD)
        pressDone()
        assertEquals(1, built.size)

        stopApp()
        startApp()

        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        assertEquals(1, built.size, "a locked code builds nothing new")
    }

    @Test
    fun aCheckThatEndsAfterTheAppLeftTheForegroundDoesNotUnlockTheCode() {
        val answer = CompletableDeferred<PasswordCheck>()
        showLumioCode(check = { answer.await() })
        type(APP_PASSWORD)
        pressDone()

        stopApp()
        answer.complete(PasswordCheck.Accepted)
        compose.waitForIdle()
        startApp()

        assertTrue(built.isEmpty(), "a check that ends after the app stopped does not unlock the code")
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
    }

    /** Opens PAIR WALLET for [wallet] on the LUMIO code, and stops at the App Password step. */
    private fun showLumioCode(
        check: suspend (CharArray) -> PasswordCheck,
        onUnavailable: () -> Unit = {},
    ) {
        compose.setContent {
            LumenTheme {
                PairWizard(
                    wallets = listOf(wallet),
                    profiles = emptyMap(),
                    preselected = wallet,
                    onDone = {},
                    onLeave = {},
                    lumioParts = { meta ->
                        built += meta
                        listOf("ur:x-airvault-pairing/1-1/aa")
                    },
                    checkPassword = check,
                    onUnavailable = onUnavailable,
                )
            }
        }
        compose.onNodeWithText("LUMIO (AIRVAULT COMPANION)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Show Code", ignoreCase = true).performClick()
        compose.waitForIdle()
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

    /** Brings the activity back to the foreground. The Compose test owner finds no hierarchy while the activity is stopped. */
    private fun startApp() = moveActivity(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)

    private fun moveActivity(vararg events: Lifecycle.Event) {
        compose.runOnIdle {
            val lifecycle = compose.activity.lifecycle as LifecycleRegistry
            events.forEach { lifecycle.handleLifecycleEvent(it) }
        }
        compose.waitForIdle()
    }

    private fun mnemonic() = WalletMeta(
        id = "w1",
        name = "Seed wallet",
        createdAtMs = 0L,
        deviceGenerated = true,
        accountCount = 1,
        masterFingerprintHex = "1a2b3c4d",
        accountKeys = listOf(AccountXpub(publicKey = ByteArray(33), chainCode = ByteArray(32), parentFingerprint = 0)),
        kind = WalletKind.MNEMONIC,
        autoNameBase = "Seed wallet",
    )
}
