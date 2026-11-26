package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.search.SearchRow
import app.lumio.ui.search.SearchSection
import app.lumio.ui.search.SearchSheet
import app.lumio.ui.search.SearchTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the search sheet: with results, with no match and with the Trending tab. Recorded on the Linux runner, with reduced motion, so each
 * golden is a still frame. Home's goldens are in [HomeScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeSearchScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchResults() = compose.clearAirSnapshot("search_results") { SearchPage(query = "usd", tab = SearchTab.MAIN, sections = sections()) }

    @Test
    fun searchNoMatch() = compose.clearAirSnapshot("search_no_match") { SearchPage(query = "xyz", tab = SearchTab.MAIN, sections = emptyList()) }

    @Test
    fun searchTrending() = compose.clearAirSnapshot("search_trending") {
        SearchPage(query = "", tab = SearchTab.TRENDING, sections = listOf(SearchSection(label = null, rows = trendingRows())))
    }

    @Composable
    private fun SearchPage(query: String, tab: SearchTab, sections: List<SearchSection>) {
        SearchSheet(
            query = query,
            onQuery = {},
            tab = tab,
            onTab = {},
            sections = sections,
        )
    }

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
}
