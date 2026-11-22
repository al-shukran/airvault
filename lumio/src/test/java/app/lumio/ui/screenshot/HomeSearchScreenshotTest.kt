package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.MarketHero
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.search.SearchRow
import app.lumio.ui.search.SearchSection
import app.lumio.ui.search.SearchSheet
import app.lumio.ui.search.SearchTab
import app.lumio.watchlist.WatchlistSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Home tab, its sort sheet and the search sheet: light and dark, with a watchlist with its day's tick charts, an empty one and the test
 * side, the hero with a live figure and with a kept one, and the search with results, with no match and with the Trending tab. Recorded on the Linux
 * runner, with reduced motion, so each golden is a still frame.
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
    fun homeTestnet() = compose.clearAirSnapshot("home_watchlist_testnet") { HomePage(rows = rows(), testnet = true, hero = NO_HERO) }

    @Test
    fun homeKeptMarket() = compose.clearAirSnapshot("home_market_kept") { HomePage(rows = rows(), testnet = false, hero = KEPT_HERO) }

    @Test
    fun sortSheet() = compose.clearAirSnapshot("home_sort_sheet") {
        Box(modifier = Modifier.fillMaxSize()) {
            HomePage(rows = rows(), testnet = false)
            WatchlistSortSheet(visible = true, selected = WatchlistSort.CHANGE_24H, onSelect = {}, onClose = {})
        }
    }

    @Test
    fun searchResults() = compose.clearAirSnapshot("search_results") { SearchPage(query = "usd", tab = SearchTab.MAIN, sections = sections()) }

    @Test
    fun searchNoMatch() = compose.clearAirSnapshot("search_no_match") { SearchPage(query = "xyz", tab = SearchTab.MAIN, sections = emptyList()) }

    @Test
    fun searchTrending() = compose.clearAirSnapshot("search_trending") {
        SearchPage(query = "", tab = SearchTab.TRENDING, sections = listOf(SearchSection(label = null, rows = trendingRows())))
    }

    @Composable
    private fun HomePage(rows: List<WatchlistRow>, testnet: Boolean, hero: MarketHero = LIVE_HERO) {
        Column(modifier = Modifier.fillMaxSize().padding(top = ClearAirSpacing.md)) {
            HomeScreen(
                hero = hero,
                rows = rows,
                sort = WatchlistSort.MARKET_CAP,
                testnet = testnet,
                onSend = {},
                onReceive = {},
                onSort = {},
            )
        }
    }

    @Composable
    private fun SearchPage(query: String, tab: SearchTab, sections: List<SearchSection>) {
        SearchSheet(
            query = query,
            onQuery = {},
            tab = tab,
            onTab = {},
            sections = sections,
            onClose = {},
        )
    }

    private fun rows(): List<WatchlistRow> = listOf(
        WatchlistRow(
            name = "Bitcoin",
            detail = "BTC · 0.2104 held",
            priceUsd = "$108,420.35",
            change24hPct = 1.82,
            ticks = listOf(104_200.0, 105_900.0, 105_100.0, 106_800.0, 107_300.0, 106_500.0, 108_000.0, 108_420.35),
        ),
        WatchlistRow(
            name = "Ethereum",
            detail = "ETH · 22.23 held",
            priceUsd = "$4,285.70",
            change24hPct = -2.41,
            ticks = listOf(4_390.0, 4_360.0, 4_340.0, 4_310.0, 4_330.0, 4_300.0, 4_290.0, 4_285.70),
        ),
        WatchlistRow(name = "Solana", detail = "SOL · 31.5 held", priceUsd = "$184.20", change24hPct = 4.10),
        WatchlistRow(name = "Algorand", detail = "ALGO", priceUsd = null, change24hPct = null),
    )

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

    private fun trendingRows(): List<SearchRow> = listOf(
        SearchRow(key = "0-BONK", title = "Bonk", detail = "BONK · #103 · \$0.00 · ▼ 3.27%", onOpen = null),
        SearchRow(key = "1-NEW", title = "New Coin", detail = "NEW", onOpen = null),
    )

    private companion object {
        val LIVE_HERO = MarketHero(total = "\$3.41 T", change24hPct = 2.14, freshness = "Live · updated 8 s ago")
        val KEPT_HERO = MarketHero(total = "\$3.41 T", change24hPct = 2.14, freshness = "Last known · updated 3 h ago")
        val NO_HERO = MarketHero(total = null, change24hPct = null, freshness = null)
    }
}
