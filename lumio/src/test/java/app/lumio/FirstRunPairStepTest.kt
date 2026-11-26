package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.lensly.app.VaultHolder
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.navigation.LenslyRoute
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.ui.firstrun.FirstRunStep
import app.lumio.ui.kit.lumioComposable
import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first run ends on the Pair with Lensly step (mock step 10), not on Home. Device Protection leads to it, it sits above Home, and the system back
 * leaves it for Home, so an owner with no Lensly at hand is never held. The graph is the real first-run graph, so a first run that stops routing
 * through the pair step fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class FirstRunPairStepTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), FakeClock(), KdfParams(memoryKiB = 8 * 1024, iterations = 1)) }
    private val holder by lazy { VaultHolder(vault) }
    private lateinit var nav: NavHostController

    @Test
    fun deviceProtectionLeadsToThePairStepAboveHome() {
        showDeviceProtection()

        compose.onNodeWithText("Not Now").performClick()
        compose.waitForIdle()

        assertEquals(FirstRunStep.Pair.route, currentRoute())
        assertEquals(listOf(LumioRoute.HOME, FirstRunStep.Pair.route), backStack().takeLast(2))
        compose.onNodeWithText("Pair with Lensly").assertExists()
    }

    @Test
    fun systemBackOnThePairStepLeavesForHome() {
        showDeviceProtection()
        compose.onNodeWithText("Not Now").performClick()
        compose.waitForIdle()

        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals(LumioRoute.HOME, currentRoute())
    }

    private fun showDeviceProtection() {
        compose.setContent {
            ClearAirTheme {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = LenslyRoute.HIDDEN) {
                    navigation(startDestination = FirstRunStep.DeviceProtection.route, route = LenslyRoute.HIDDEN) {
                        firstRunRoutes(nav, holder, PairDraft(), home = LumioRoute.HOME)
                        lumioComposable(LumioRoute.HOME) { Text("Home") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun currentRoute(): String? = nav.currentBackStackEntry?.destination?.route

    private fun backStack(): List<String?> = nav.currentBackStack.value.map { it.destination.route }
}
