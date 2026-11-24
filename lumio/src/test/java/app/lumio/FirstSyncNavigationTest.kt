package app.lumio

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import app.lensly.app.VaultHolder
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.navigation.GateOutcome
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallets
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import after a pairing is the last step of the onboarding. Only Continue leaves it, for the wallet. View Accounts opens the wallet on top of
 * the import, and Back, the top bar's arrow and the system back stay on the import. The graph is the real first-sync route with the route names of
 * the Lumio graph, so the back stack is the one the owner's taps build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class FirstSyncNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var nav: NavHostController
    private lateinit var walletId: String

    @Test
    fun viewAccountsOpensTheWalletAboveTheImportAndSystemBackReturnsToIt() {
        showImport()

        compose.onNodeWithText("View Accounts").performClick()
        compose.waitForIdle()

        assertEquals(LumioRoute.WALLET, currentRoute())
        assertTrue(LumioRoute.FIRST_SYNC in backStack(), "The import stays under the accounts, so the onboarding is still active")

        systemBack()
        assertEquals(LumioRoute.FIRST_SYNC, currentRoute())
    }

    @Test
    fun systemBackAndTheTopBarArrowStayOnTheImport() {
        showImport()

        systemBack()
        assertEquals(LumioRoute.FIRST_SYNC, currentRoute())

        compose.onNodeWithContentDescription("Back to Pair with Lensly").performClick()
        compose.waitForIdle()
        assertEquals(LumioRoute.FIRST_SYNC, currentRoute())
    }

    @Test
    fun onlyContinueFinishesTheOnboardingAndOpensTheWallet() {
        showImport()
        compose.onNodeWithText("View Accounts").performClick()
        compose.waitForIdle()
        systemBack()
        assertEquals(LumioRoute.FIRST_SYNC, currentRoute())

        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()

        assertEquals(LumioRoute.WALLET, currentRoute())
        assertFalse(LumioRoute.FIRST_SYNC in backStack(), "Continue leaves the import, so the onboarding is over")
    }

    @Test
    fun aWalletDeletedWhileTheImportWasAwayLeavesForTheWalletsList() {
        showImport()
        compose.onNodeWithText("View Accounts").performClick()
        compose.waitForIdle()
        PairedWallets.delete(checkNotNull(holder.openSession()), walletId)

        systemBack()

        compose.waitUntil(timeoutMillis = 10_000) { currentRoute() == LumioRoute.WALLETS }
        assertEquals(LumioRoute.WALLETS, currentRoute())
    }

    /** Opens the import of a wallet that the vault holds, as the pairing review does after it saves the wallet. */
    private fun showImport() {
        walletId = pairedWallet()
        setGraph()
        compose.runOnUiThread { nav.navigate(LumioRoute.firstSync(walletId)) }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("View Accounts").fetchSemanticsNodes().isNotEmpty() }
    }

    /** An open vault with one paired wallet. The first run makes the vault, and the wallet is saved as the pairing review saves it. */
    private fun pairedWallet(): String = runBlocking {
        assertEquals(GateOutcome.Opened, createFirstVault(holder, PASSWORD.toCharArray(), context))
        val session = checkNotNull(holder.openSession())
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Account 0"))), nowMs = 100L).id
    }

    private fun setGraph() {
        compose.setContent {
            ClearAirTheme {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = LumioRoute.WALLETS) {
                    composable(LumioRoute.WALLETS) { Text("Accounts root") }
                    composable(
                        route = LumioRoute.FIRST_SYNC,
                        arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
                    ) { entry ->
                        FirstSyncRoute(
                            navController = nav,
                            holder = holder,
                            walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty(),
                            mode = NetworkMode.TESTNET,
                            onLock = {},
                        )
                    }
                    composable(
                        route = LumioRoute.WALLET,
                        arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
                    ) { Text("Wallet page") }
                }
            }
        }
    }

    private fun currentRoute(): String? = nav.currentBackStackEntry?.destination?.route

    private fun backStack(): List<String?> = nav.currentBackStack.value.map { it.destination.route }

    private fun systemBack() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
