package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.theme.ClearAirSpacing
import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.search.SearchRow
import app.lumio.ui.search.SearchSection
import app.lumio.ui.search.SearchSheet
import app.lumio.ui.search.SearchTab
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Home tab, its sort sheet and the search sheet: light and dark, with a watchlist, an empty one and the test side, and the search
 * with results and with no match. Recorded on the Linux runner, with reduced motion, so each golden is a still frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSearchScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun home() = compose.clearAirSnapshot("home_watchlist") { HomePage(rows = rows(), testnet = false) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun homeLight() = compose.clearAirSnapshot("home_watchlist_light") { HomePage(rows = rows(), testnet = false) }

    @Test
    fun homeEmpty() = compose.clearAirSnapshot("home_watchlist_empty") { HomePage(rows = emptyList(), testnet = false) }

    @Test
    fun homeTestnet() = compose.clearAirSnapshot("home_watchlist_testnet") { HomePage(rows = rows(), testnet = true) }

    @Test
    fun sortSheet() = compose.clearAirSnapshot("home_sort_sheet") {
        Box(modifier = Modifier.fillMaxSize()) {
            HomePage(rows = rows(), testnet = false)
            WatchlistSortSheet(visible = true, selected = WatchlistSort.CHANGE_24H, onSelect = {}, onClose = {})
        }
    }

    @Test
    fun searchResults() = compose.clearAirSnapshot("search_results") { SearchPage(query = "usd", sections = sections()) }

    @Test
    fun searchNoMatch() = compose.clearAirSnapshot("search_no_match") { SearchPage(query = "xyz", sections = emptyList()) }

    @Composable
    private fun HomePage(rows: List<WatchlistRow>, testnet: Boolean) {
        Column(modifier = Modifier.fillMaxSize().padding(top = ClearAirSpacing.md)) {
            HomeScreen(
                rows = rows,
                sort = WatchlistSort.MARKET_CAP,
                testnet = testnet,
                onSend = {},
                onReceive = {},
                onSort = {},
                onOpen = {},
            )
        }
    }

    @Composable
    private fun SearchPage(query: String, sections: List<SearchSection>) {
        SearchSheet(
            query = query,
            onQuery = {},
            tab = SearchTab.MAIN,
            onTab = {},
            sections = sections,
            onClose = {},
        )
    }

    private fun rows(): List<WatchlistRow> = listOf(
        WatchlistRow(asset = coin("bip122:000000000019d6689c085ae165831e93"), name = "Bitcoin", detail = "BTC · 0.2104 held", priceUsd = "$108,420.35", change24hPct = 1.82),
        WatchlistRow(asset = coin("eip155:1"), name = "Ethereum", detail = "ETH · 22.23 held", priceUsd = "$4,285.70", change24hPct = -2.41),
        WatchlistRow(asset = coin("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"), name = "Solana", detail = "SOL · 31.5 held", priceUsd = "$184.20", change24hPct = 4.10),
        WatchlistRow(asset = coin("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8="), name = "Algorand", detail = "ALGO", priceUsd = null, change24hPct = null),
    )

    private fun coin(chain: String): WatchedAsset = WatchedAsset(NetworkId(ChainId(chain), testnet = false))

    private fun sections(): List<SearchSection> = listOf(
        SearchSection(
            label = "Tokens",
            rows = listOf(SearchRow(key = "usd", title = "USD Coin", detail = "USDC", onOpen = {})),
        ),
        SearchSection(
            label = "Your Accounts",
            rows = listOf(SearchRow(key = "acc", title = "Ethereum", detail = "Air 1", onOpen = {})),
        ),
    )
}
