package app.lensly.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.DecoyVault
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.theme.LumenTheme
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PROMPT = "Enter your Application Password to show the secret."
private const val WARNING = "Keep It Private"

/**
 * The Show Secret password step, through the real route, its view model and the vault holder, on a real vault. Only an unlock promotes the
 * Decoy Vault, so a Duress Password typed here is a wrong try: it is refused, the open session stays, and the key file is not touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class ShowSecretDuressTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault
    private lateinit var holder: VaultHolder
    private lateinit var nav: NavHostController

    private fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        holder = VaultHolder(vault)
    }

    @Test
    fun theDuressPasswordIsRefusedAndPromotesNothingWhileTheAppPasswordGoesOn() {
        setUp()
        val realWallet = runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = unlock(APP)
            val wallet = real.createWallet()
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            holder.adopt(real, holder.unlockEpoch())
            wallet
        }
        val session = holder.openSession()
        val keyFile = layout.vaultKey.readBytes()
        showSecret(realWallet)

        refused(DURESS)

        assertSame(session, holder.openSession())
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        assertEquals(listOf(realWallet), session!!.listWallets())

        // The same screen takes the App Password, so the refusal above is the check's and not a screen that never answers.
        type(APP)
        pressDone()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(WARNING).fetchSemanticsNodes().isNotEmpty() }
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        holder.lock()
        assertTheLockedScreenGoesToTheGate()
        val reopened = unlock(APP)
        assertEquals(0, reopened.side)
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun aSecondDuressPasswordOfThePromotedVaultIsRefusedToo() {
        setUp()
        val (decoyWallet, secondDecoy) = runBlocking {
            assertEquals(CreateResult.Created, vault.create(APP.toCharArray()))
            val real = unlock(APP)
            assertEquals(DuressResult.Enabled, vault.setDuress(real, DURESS.toCharArray()) { DecoyVault.populate(it) })
            real.close()
            // The Duress Password unlocks the decoy, which is the vault from then on and is opened under that same password.
            val promoted = unlock(DURESS)
            val decoy = promoted.listWallets().single()
            assertEquals(DuressResult.Enabled, vault.setDuress(promoted, SECOND.toCharArray()) { DecoyVault.populate(it) })
            holder.adopt(promoted, holder.unlockEpoch())
            decoy to promoted.side
        }
        awaitCleanup()
        val session = holder.openSession()
        val keyFile = layout.vaultKey.readBytes()
        showSecret(decoyWallet)

        refused(SECOND)

        assertSame(session, holder.openSession())
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        assertEquals(listOf(decoyWallet), session!!.listWallets())
        assertEquals(secondDecoy, session.side)

        // The promoted vault's own password still works on the screen.
        type(DURESS)
        pressDone()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(WARNING).fetchSemanticsNodes().isNotEmpty() }
        holder.lock()
        assertTheLockedScreenGoesToTheGate()
        val reopened = unlock(DURESS)
        assertEquals(listOf(decoyWallet), reopened.listWallets())
        reopened.close()
    }

    private fun showSecret(walletId: String) {
        compose.setContent {
            LumenTheme {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = "show") {
                    composable("show") { ShowSecretRoute(nav, holder, walletId) }
                    // The route a closed session goes to. Without it, a screen that recomposes after the lock throws.
                    composable(HiddenRoute.GATE) {}
                }
            }
        }
        compose.onNodeWithText(PROMPT).assertExists()
    }

    /**
     * A screen that recomposes once the session is closed goes to the gate, and the NavHost must have one. Pressing Show makes that
     * happen now, not whenever a later recomposition does, so the outcome does not depend on how fast the machine is.
     */
    private fun assertTheLockedScreenGoesToTheGate() {
        compose.onNodeWithText("Show").performClick()
        // The secret is read off the main thread, so the navigation follows the click by an unknown time.
        compose.waitUntil(WAIT_MS) { nav.currentDestination?.route == HiddenRoute.GATE }
    }

    /** Types [password] at the password step and waits until the vault counted the try and the screen is asking again. */
    private fun refused(password: String) {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val before = vault.failureCountForTesting()
        type(password)
        pressDone()
        compose.waitUntil(WAIT_MS) { vault.failureCountForTesting() > before }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Checking").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(PROMPT).assertExists()
        assertEquals(0, compose.onAllNodesWithText(WARNING).fetchSemanticsNodes().size)
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
    }

    private fun unlock(password: String): VaultSession = runBlocking {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        (vault.unlock(password.toCharArray()) as UnlockResult.Unlocked).session
    }

    private fun type(text: String) {
        text.forEach { compose.onNodeWithContentDescription(it.toString()).performClick() }
    }

    private fun pressDone() {
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitForIdle()
    }

    /** The cleanup that a promotion leaves removes its marker last. */
    private fun awaitCleanup() {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (layout.promotionMarker.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertFalse(layout.promotionMarker.exists(), "the cleanup of the promotion finished")
    }

    private companion object {
        // Lower-case letters and digits only, so every key is on the keyboard's first page.
        const val APP = "q7zk2mxv9pw4tb"
        const val DURESS = "h3rn8cjd5yfu6l"
        const val SECOND = "w9bx4vgs2ntq7e"
        const val WAIT_MS = 15_000L
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
