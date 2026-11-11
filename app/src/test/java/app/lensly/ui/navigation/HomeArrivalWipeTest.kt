package app.lensly.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.app.WalletDraftViewModel
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.theme.LenslyTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Arriving back on HOME without saving ends the create or restore flow, so the words the flow held are wiped. Leaving the
 * words screen with Back must not keep them in memory until the hidden graph closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class HomeArrivalWipeTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    // BIP39 vector for 256 zero bits of entropy.
    private val artWords = List(23) { "abandon" } + "art"

    @Test
    fun backingOutToHomeWithoutSavingWipesTheWords() {
        val holder = openHolder()
        lateinit var nav: NavHostController
        compose.setContent {
            LenslyTheme {
                nav = rememberNavController()
                val controller = remember { nav }
                NavHost(navController = controller, startDestination = LenslyRoute.CAMERA) {
                    composable(LenslyRoute.CAMERA) {}
                    hiddenGraph(controller, holder)
                }
            }
        }
        compose.runOnIdle { nav.navigate(LenslyRoute.HIDDEN) { launchSingleTop = true } }
        compose.waitForIdle()
        assertEquals(HiddenRoute.HOME, nav.currentBackStackEntry?.destination?.route)

        compose.runOnIdle { nav.navigate(HiddenRoute.WALLET_START) }
        compose.waitForIdle()
        val draft = ViewModelProvider(nav.getBackStackEntry(LenslyRoute.HIDDEN))[WalletDraftViewModel::class.java].draft
        compose.runOnIdle { draft.setTypedWords(artWords) }
        assertEquals(artWords, draft.words)

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()

        assertEquals(HiddenRoute.HOME, nav.currentBackStackEntry?.destination?.route)
        assertTrue(draft.words.isEmpty(), "the words are wiped on arriving at HOME")
    }

    /** A vault with an open session, so HOME composes instead of sending the owner back to the gate. */
    private fun openHolder(): VaultHolder {
        val session = VaultSession(VaultLayout(tmp.root), ByteArray(32) { (it + 5).toByte() })
        val vault = Vault(
            VaultLayout(File(tmp.root, "vault")),
            MemoryKeystore(),
            FakeClock(),
            KdfParams(memoryKiB = 8 * 1024, iterations = 1),
        )
        return VaultHolder(vault).also { it.adopt(session, it.unlockEpoch()) }
    }
}
