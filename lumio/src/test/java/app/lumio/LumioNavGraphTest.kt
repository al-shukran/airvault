package app.lumio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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

    @Test
    fun theSolanaAndAlgorandSendsTakeTheCoinTheyStartOn() {
        compose.setContent {
            nav = rememberNavController()
            LumioNavHost(navController = nav)
        }
        compose.waitForIdle()

        for (route in listOf(LumioRoute.SOLANA_SEND, LumioRoute.ALGORAND_SEND)) {
            val coin = checkNotNull(nav.graph.allDestinations().single { it.route == route }.arguments[LumioRoute.SEND_COIN_ARG]) { "$route has no coin argument" }
            assertTrue(coin.isNullable, "The coin of $route is left out for the native coin.")
        }
    }

    /** Every destination of the graph, nested graphs included. */
    private fun NavGraph.allDestinations(): List<NavDestination> =
        flatMap { node -> listOf(node) + if (node is NavGraph) node.allDestinations() else emptyList() }

    private fun placeholders(route: String): Set<String> = PLACEHOLDER.findAll(route).map { it.groupValues[1] }.toSet()

    private companion object {
        val PLACEHOLDER = Regex("\\{([^}]+)}")
    }
}
