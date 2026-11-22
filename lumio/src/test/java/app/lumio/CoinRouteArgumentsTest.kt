package app.lumio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each coin route declares exactly the arguments its path names. Navigation throws while building the graph when a route
 * lacks a declared argument, which crashed Lumio on opening.
 */
class CoinRouteArgumentsTest {
    private fun placeholders(route: String): Set<String> = Regex("\\{([^}]+)}").findAll(route).map { it.groupValues[1] }.toSet()

    @Test
    fun walletCoinRoutesTakeTheWalletAndTheCoin() {
        val names = coinArguments().map { it.name }.toSet()
        assertEquals(placeholders(LumioRoute.COIN), names)
        assertEquals(placeholders(LumioRoute.HOLDINGS), names)
    }

    @Test
    fun coinActivityTakesTheCoinOnly() {
        assertEquals(placeholders(LumioRoute.ACTIVITY_COIN), coinAssetArguments().map { it.name }.toSet())
    }
}
