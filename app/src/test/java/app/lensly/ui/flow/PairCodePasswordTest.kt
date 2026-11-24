package app.lensly.ui.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import app.lensly.app.AppNotInstalled
import app.lensly.app.NoAppsInstalled
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.wallet.AccountXpub
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val APP_PASSWORD = "sesame"
private const val PASSWORD_PROMPT = "Application Password"
private const val LUMIO_OPTION = "LUMIO (AIRVAULT COMPANION)"
private const val RABBY_OPTION = "RABBY (AIRGAP VAULT)"
private const val METAMASK_OPTION = "METAMASK (QR-BASED)"
private const val QR_CODE = "QR code"

/**
 * Every pairing code is built only once the App Password is right. A wrong password builds nothing, an unavailable vault leaves for
 * its notice, and the code locks again when the app leaves the foreground, as Show Secret does.
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
        showCode(check = { password -> if (String(password) == APP_PASSWORD) PasswordCheck.Accepted else PasswordCheck.Rejected })

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
        showCode(check = { PasswordCheck.Unavailable }, onUnavailable = { unavailable++ })

        type(APP_PASSWORD)
        pressDone()

        assertEquals(1, unavailable)
        assertTrue(built.isEmpty())
    }

    @Test
    fun theCodeLocksAgainWhenTheAppLeavesTheForeground() {
        showCode(check = { PasswordCheck.Accepted })
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
        showCode(check = { answer.await() })
        type(APP_PASSWORD)
        pressDone()

        stopApp()
        answer.complete(PasswordCheck.Accepted)
        compose.waitForIdle()
        startApp()

        assertTrue(built.isEmpty(), "a check that ends after the app stopped does not unlock the code")
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
    }

    @Test
    fun theRabbyCodeIsNotComposedBeforeTheAppPasswordIsRight() = assertCodeIsBehindThePassword(RABBY_OPTION)

    @Test
    fun theMetaMaskCodeIsNotComposedBeforeTheAppPasswordIsRight() = assertCodeIsBehindThePassword(METAMASK_OPTION)

    @Test
    fun aWalletWithOnlyBitcoinPairsWithLumio() {
        val bitcoinOnly = mnemonic().copy(accountCount = 0, accountKeys = emptyList())
        showCode(paired = bitcoinOnly, check = { PasswordCheck.Accepted })
        type(APP_PASSWORD)
        pressDone()

        waitForQrCode()
        assertEquals(listOf(bitcoinOnly), built)
    }

    /**
     * Lumio refuses a pairing with no account, so without any installed App no AirVault code is built. The code step says so and opens the
     * wallet's Apps, and no Done is left to press.
     */
    @Test
    fun aLumioCodeWithNoInstalledAppShowsItsNoticeAndOpensTheApps() {
        val opened = mutableListOf<String>()
        showCode(
            LUMIO_OPTION,
            check = { PasswordCheck.Accepted },
            lumioParts = { throw NoAppsInstalled() },
            onOpenApps = { opened += it },
        )
        type(APP_PASSWORD)
        pressDone()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("No App Is Installed", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done", ignoreCase = true).assertDoesNotExist()

        compose.onNodeWithText("Open Apps").performClick()

        assertEquals(listOf(wallet.id), opened)
    }

    /**
     * Without its Ethereum App, a Rabby code is not built: the code step says so and opens the wallet's Apps, and no Done is left to press.
     */
    @Test
    fun aRabbyCodeWithoutTheEthereumAppShowsItsNoticeAndOpensTheApps() {
        val opened = mutableListOf<String>()
        showCode(
            RABBY_OPTION,
            check = { PasswordCheck.Accepted },
            ethereumParts = { throw AppNotInstalled(WalletApp.ETHEREUM) },
            onOpenApps = { opened += it },
        )
        type(APP_PASSWORD)
        pressDone()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Ethereum Is Not Installed", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Done", ignoreCase = true).assertDoesNotExist()

        compose.onNodeWithText("Open Apps").performClick()

        assertEquals(listOf(wallet.id), opened)
    }

    @Test
    fun theRabbyCodeLocksAgainWhenTheAppLeavesTheForeground() {
        showCode(RABBY_OPTION, check = { PasswordCheck.Accepted })
        type(APP_PASSWORD)
        pressDone()
        waitForQrCode()

        stopApp()
        startApp()

        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
    }

    /**
     * The code of [option] has no QR node until the App Password is right: none is composed for a wrong password, and the code appears
     * once the right one is typed.
     */
    private fun assertCodeIsBehindThePassword(option: String) {
        showCode(option, check = { password -> if (String(password) == APP_PASSWORD) PasswordCheck.Accepted else PasswordCheck.Rejected })
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        assertTrue(built.isEmpty(), "the code is built only after the password")

        type("wrong")
        pressDone()
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertExists()
        compose.onNodeWithContentDescription(QR_CODE, substring = true).assertDoesNotExist()
        assertTrue(built.isEmpty(), "a wrong password builds no code")

        type(APP_PASSWORD)
        pressDone()
        waitForQrCode()
        assertEquals(listOf(wallet), built)
        compose.onNodeWithText(PASSWORD_PROMPT, substring = true).assertDoesNotExist()
    }

    /** The frames of a Rabby or MetaMask code are built off the main thread, so the test waits for their QR to appear. */
    private fun waitForQrCode() = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithContentDescription(QR_CODE, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Opens PAIR WALLET for [paired] on the [option] code, and stops at the App Password step. */
    private fun showCode(
        option: String = LUMIO_OPTION,
        paired: WalletMeta = wallet,
        check: suspend (CharArray) -> PasswordCheck,
        onUnavailable: () -> Unit = {},
        lumioParts: suspend (WalletMeta) -> List<String> = { meta ->
            built += meta
            listOf("ur:x-airvault-pairing/1-1/aa")
        },
        ethereumParts: suspend (WalletMeta) -> List<String> = { meta ->
            built += meta
            listOf("ur:crypto-multi-accounts/1-1/aa")
        },
        onOpenApps: (String) -> Unit = {},
    ) {
        compose.setContent {
            LumenTheme {
                PairWizard(
                    wallets = listOf(paired),
                    profiles = emptyMap(),
                    preselected = paired,
                    onDone = {},
                    onLeave = {},
                    lumioParts = lumioParts,
                    ethereumParts = ethereumParts,
                    checkPassword = check,
                    onUnavailable = onUnavailable,
                    onOpenApps = onOpenApps,
                )
            }
        }
        compose.onNodeWithText(option).performClick()
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
