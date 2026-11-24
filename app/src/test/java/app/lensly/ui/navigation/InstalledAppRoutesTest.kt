package app.lensly.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.SessionReads
import app.lensly.app.VaultHolder
import app.lensly.app.WalletApp
import app.lensly.app.WalletStore
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guards of the App screens. An App the wallet does not have leads to the wallet's Apps screen, and the App's own entries leave the back
 * stack, so Back never returns to them. An App the wallet has keeps its screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-port-mdpi")
class InstalledAppRoutesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val key = ByteArray(32) { (it + 5).toByte() }
    private val words = List(23) { "abandon" } + "art"
    private lateinit var store: WalletStore
    private lateinit var holder: VaultHolder

    @Before
    fun openVault() {
        val session = VaultSession(VaultLayout(tmp.root), keys, key.copyOf())
        store = WalletStore(session)
        holder = VaultHolder(Vault(VaultLayout(tmp.root), keys, FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1))) { SessionReads(it) }
        assertTrue(holder.adopt(session, holder.unlockEpoch()))
    }

    @After
    fun lockVault() {
        holder.lock()
    }

    @Test
    fun anUninstalledAppLeadsToTheAppsScreenAndLeavesNoEntryOfItBehind() {
        val id = newWallet()

        val nav = show(HiddenRoute.app(id, WalletApp.ETHEREUM))

        assertRedirectedToApps(nav, id)
    }

    /** A bulk run opened directly has no App screen under it. Back from the Apps screen must not return to the bulk run. */
    @Test
    fun aBulkRunOpenedDirectlyForAnUninstalledAppLeavesNoEntryBehindForBack() {
        val id = newWallet()

        val nav = show(HiddenRoute.bulk(id, WalletApp.ETHEREUM))

        assertRedirectedToApps(nav, id)
        compose.runOnUiThread { nav.popBackStack() }
        assertEquals(LenslyRoute.CAMERA, nav.currentBackStackEntry?.destination?.route)
    }

    /** A bulk run is opened from its App's screen, so that screen is under it. The App is uninstalled while the screen stays open. */
    @Test
    fun aBulkRunOfAnUninstalledAppLeadsToTheAppsScreenAndLeavesNoEntryOfItBehind() {
        val id = newWallet()
        store.installApp(id, WalletApp.ETHEREUM)
        val nav = show(HiddenRoute.app(id, WalletApp.ETHEREUM))
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Ethereum Accounts").fetchSemanticsNodes().isNotEmpty() }

        store.uninstallApp(id, WalletApp.ETHEREUM)
        compose.runOnUiThread { nav.navigate(HiddenRoute.bulk(id, WalletApp.ETHEREUM)) }

        assertRedirectedToApps(nav, id)
    }

    /**
     * The App is uninstalled while its account list stays open, so Add Account finds no Ethereum account to add to. The owner goes to the Apps
     * screen, and the app does not crash.
     */
    @Test
    fun addingAnAccountToAnAppUninstalledWhileItsListIsOpenLeadsToTheAppsScreen() {
        val id = newWallet()
        store.installApp(id, WalletApp.ETHEREUM)
        val nav = show(HiddenRoute.app(id, WalletApp.ETHEREUM))
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Ethereum Accounts").fetchSemanticsNodes().isNotEmpty() }

        store.uninstallApp(id, WalletApp.ETHEREUM)
        compose.onNodeWithText("Add Account").performClick()

        assertRedirectedToApps(nav, id)
    }

    @Test
    fun anInstalledAppKeepsItsScreen() {
        val id = newWallet()

        val nav = show(HiddenRoute.app(id, WalletApp.BITCOIN))

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Bitcoin Accounts").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(HiddenRoute.APP, nav.currentBackStackEntry?.destination?.route)
    }

    /** A wallet with its Bitcoin account only, as [WalletStore.create] leaves it. */
    private fun newWallet(): String = store.create(
        name = "FIRST",
        deviceGenerated = false,
        words = words,
        passphrase = "FIRST",
        nowMs = 1L,
    )

    /** Shows the hidden graph over the camera and opens [route] in it. Returns the controller, for the back stack. */
    private fun show(route: String): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            val controller = remember { nav }
            NavHost(navController = controller, startDestination = LenslyRoute.CAMERA) {
                composable(LenslyRoute.CAMERA) {}
                hiddenGraph(controller, holder)
            }
        }
        compose.runOnUiThread { nav.navigate(route) }
        return nav
    }

    private fun assertRedirectedToApps(nav: NavHostController, id: String) {
        compose.waitUntil(timeoutMillis = 5_000) { nav.currentBackStackEntry?.destination?.route == HiddenRoute.APPS }
        assertEquals(id, nav.currentBackStackEntry?.arguments?.getString(HiddenRoute.WALLET_ID_ARG))
        assertTrue(
            nav.currentBackStack.value.none { it.destination.route == HiddenRoute.APP || it.destination.route == HiddenRoute.BULK },
            "The App's own entries leave the back stack, so Back does not return to them.",
        )
    }
}
