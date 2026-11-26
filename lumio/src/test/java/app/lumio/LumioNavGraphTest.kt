package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.airvault.core.model.AccountId
import app.lumio.send.algorand.AlgorandAssets
import app.lumio.send.solana.SolanaAssets
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real Lumio navigation graph builds, and every route it declares takes exactly the arguments its pattern names. Navigation throws while
 * it builds a graph whose route arguments do not match the route, which crashed Lumio when the Hidden Area opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class LumioNavGraphTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: NavHostController

    @Test
    fun theGraphBuildsAndEveryRouteTakesExactlyItsPatternArguments() {
        compose.setContent {
            nav = rememberNavController()
            LumioNavHost(navController = nav)
        }
        compose.waitForIdle()

        val destinations = nav.graph.allDestinations().filter { it.route != null }
        assertTrue(destinations.map { it.route }.containsAll(listOf(LumioRoute.ACTIVITY_COIN, LumioRoute.COIN, LumioRoute.COIN_PRICE, LumioRoute.HOLDINGS)))
        for (destination in destinations) {
            val route = destination.route!!
            assertEquals(placeholders(route), destination.arguments.keys, "The arguments of $route")
        }
    }

    /** What each send route was given as its starting coin, by chain, in the order that the routes composed. */
    private val started = mutableMapOf<String, MutableList<String>>()

    private fun startOn(route: String, chain: String): String {
        started.clear()
        compose.runOnUiThread { nav.navigate(route) }
        // The vault is locked, so a send sends the owner to the gate on a later frame: stop on the frame that composes the send.
        repeat(MAX_FRAMES) { if (started[chain].isNullOrEmpty()) compose.mainClock.advanceTimeByFrame() }
        return checkNotNull(started[chain]?.lastOrNull()) { "$route did not open the $chain send." }
    }

    @Test
    fun theSolanaAndAlgorandSendsStartOnTheCoinTheRouteNames() {
        compose.setContent {
            CompositionLocalProvider(LocalSendStartKey provides { chain, key -> started.getOrPut(chain) { mutableListOf() }.add(key) }) {
                nav = rememberNavController()
                LumioNavHost(navController = nav)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val solana = AccountId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp:addr")
        val algorand = AccountId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:addr")

        assertEquals(SolanaAssets.SOL_KEY, startOn(LumioRoute.solanaSend("w", solana), "solana"))
        assertEquals("Mint1111", startOn(LumioRoute.solanaSend("w", solana, mint = "Mint1111"), "solana"))
        assertEquals(AlgorandAssets.ALGO_KEY, startOn(LumioRoute.algorandSend("w", algorand), "algorand"))
        assertEquals("31566704", startOn(LumioRoute.algorandSend("w", algorand, assetId = "31566704"), "algorand"))
    }

    /** Every destination of the graph, nested graphs included. */
    private fun NavGraph.allDestinations(): List<NavDestination> =
        flatMap { node -> listOf(node) + if (node is NavGraph) node.allDestinations() else emptyList() }

    private fun placeholders(route: String): Set<String> = PLACEHOLDER.findAll(route).map { it.groupValues[1] }.toSet()

    private companion object {
        val PLACEHOLDER = Regex("\\{([^}]+)}")
        const val MAX_FRAMES = 20
    }
}
