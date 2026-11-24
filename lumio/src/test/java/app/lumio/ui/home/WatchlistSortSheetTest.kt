package app.lumio.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.watchlist.WatchlistSort
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The watchlist sort sheet (B2): its two orders with what comes first in each, and a choice that reports itself. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class WatchlistSortSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSheetOffersBothOrdersAndAChoiceReportsItself() {
        var picked: WatchlistSort? = null
        compose.setContent {
            ClearAirTheme {
                WatchlistSortSheet(visible = true, selected = WatchlistSort.MARKET_CAP, onSelect = { picked = it }, onClose = {})
            }
        }

        compose.onNodeWithText("Sort Watchlist").assertIsDisplayed()
        compose.onNodeWithText("Largest first").assertIsDisplayed()
        compose.onNodeWithText("Biggest gain first").assertIsDisplayed()
        compose.onNodeWithText("24h Change").performClick()
        assertEquals(WatchlistSort.CHANGE_24H, picked)
    }
}
