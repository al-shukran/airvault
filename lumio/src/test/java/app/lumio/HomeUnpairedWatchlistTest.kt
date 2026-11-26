package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.theme.ClearAirTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home is the watchlist, and the watchlist belongs to the app, not to a paired wallet. With an open vault and no wallet paired, Home shows the
 * watchlist header, the first-run rows and the Add to Watchlist row, as it does for a paired wallet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class, qualifiers = "w360dp-h4000dp")
class HomeUnpairedWatchlistTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: LumioApp get() = RuntimeEnvironment.getApplication() as LumioApp

    @After
    fun lock() {
        app.vaultHolder.lock()
    }

    @Test
    fun homeWithNoWalletPairedShowsTheWatchlistTheFirstRunRowsAndTheAddRow() {
        openVault()
        compose.setContent {
            ClearAirTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = LumioRoute.HOME) {
                    composable(LumioRoute.HOME) {
                        HomeRoute(navController = nav, holder = app.vaultHolder, selection = NetworkSelection(), listVersion = 0, onLock = {})
                    }
                }
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Add to Watchlist").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("Watchlist · 4", ignoreCase = true).assertCountEquals(1)
        for (name in listOf("Bitcoin", "Ethereum", "Solana", "Algorand")) compose.onAllNodesWithText(name).assertCountEquals(1)
        compose.onAllNodesWithText("Send").assertCountEquals(1)
    }

    /** Opens a vault with no wallet paired, as the one Device Protection makes before the first pairing. */
    private fun openVault() = runBlocking {
        val vault = Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        val opened = vault.unlock(PASSWORD.toCharArray())
        assertIs<UnlockResult.Unlocked>(opened)
        val holder = app.vaultHolder
        assertEquals(true, holder.adopt(opened.session, holder.unlockEpoch()))
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
    }
}
