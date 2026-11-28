package app.lumio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import app.airvault.core.model.ChainId
import app.lensly.ui.theme.ClearAirPalette
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.net.NetworkId
import app.lumio.ui.kit.TEXT_ACTION_ICON_TAG
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The owner's rules for a watchlist row, on Home at the 360 dp width where they bite: whole names, whole prices, no repeated ticker, and the add. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h4000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistRowTest {
    @get:Rule
    val compose = createComposeRule()

    private var added = 0
    private var restored = 0

    private fun home(rows: List<WatchlistRow>?, footnote: String? = null) = compose.setContent {
        ClearAirTheme {
            Column(modifier = Modifier.testTag("home")) {
                HomeScreen(
                    rows = rows,
                    sort = WatchlistSort.MARKET_CAP,
                    testnet = false,
                    footnote = footnote,
                    onSend = {},
                    onReceive = {},
                    onSort = {},
                    onAdd = { added++ },
                    onRestore = { restored++ },
                    onOpen = {},
                )
            }
        }
    }

    private fun row(name: String, ticker: String, price: String = "$1.0000", change: Double = 0.0, index: Int = 0) = WatchlistRow(
        asset = WatchedAsset(NetworkId(ChainId("eip155:1"), testnet = false), contract = "0x$index"),
        name = name,
        ticker = ticker,
        icon = AssetIconSpec.Bitcoin,
        priceUsd = price,
        change24hPct = change,
    )

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun aLongNameWrapsInFullAndIsNeverCut() {
        val name = "Invesco Short Duration US Government Securities Fund"
        home(listOf(row(name, "USTB")))
        val layout = compose.onNodeWithText(name, useUnmergedTree = true).layout()
        assertTrue("the name wraps", layout.lineCount > 1)
        assertFalse("the name is not clipped", layout.hasVisualOverflow)
        assertFalse("the name has no ellipsis", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun theTickerLineIsLeftOutWhenItIsTheName() {
        home(listOf(row("BNB", "BNB"), row("Bitcoin", "BTC", index = 1)))
        compose.onAllNodesWithText("BNB").assertCountEquals(1)
        compose.onAllNodesWithText("BTC").assertCountEquals(1)
    }

    @Test
    fun aPriceWiderThanTheColumnIsNeverCut() {
        val wide = "$1,108,420.35"
        home(listOf(row("Bitcoin", "BTC", price = wide, change = 1.82), row("Tether", "USDT", price = "$0.9990", index = 1)))
        val price = compose.onNodeWithText(wide, useUnmergedTree = true).layout()
        assertEquals("the price stays on one line", 1, price.lineCount)
        assertFalse("the price is not cut", price.multiParagraph.didExceedMaxLines)
        assertTrue("the price fits its box", price.getLineRight(0) <= price.size.width + 0.5f)
    }

    @Test
    fun theChangeLineCarriesItsArrowOnlyWhenThePriceMoved() {
        home(listOf(row("Bitcoin", "BTC", change = 3.36), row("Ethereum", "ETH", change = -2.41, index = 1), row("BlackRock", "BUIDL", change = 0.0, index = 2)))
        compose.onNodeWithText("▲ 3.36%").fetchSemanticsNode()
        compose.onNodeWithText("▼ 2.41%").fetchSemanticsNode()
        compose.onNodeWithText("0.00%").fetchSemanticsNode()
    }

    @Test
    fun aRiseIsGainGreenAFallIsTheKitsRedAndNoChangeIsGrey() {
        var colours: List<Color> = emptyList()
        var palette: ClearAirPalette? = null
        compose.setContent {
            ClearAirTheme {
                palette = LocalClearAirPalette.current
                colours = ChangeTone.entries.map { changeColor(it) }
            }
        }
        compose.waitForIdle()
        val p = checkNotNull(palette)
        assertEquals(listOf(p.gain, p.rose, p.ink2), colours)
    }

    @Test
    fun anEmptyWatchlistSaysSoAndRestoresTheTopCoins() {
        home(emptyList())
        compose.onNodeWithText("Your Watchlist Is Empty").fetchSemanticsNode()
        compose.onNodeWithText("Add to Watchlist").performClick()
        compose.onNodeWithText("Restore Top Coins").performClick()
        assertEquals(1, added)
        assertEquals(1, restored)
    }

    @Test
    fun aWatchlistNotYetReadShowsNeitherTheEmptyStateNorTheHeader() {
        home(null)
        compose.onNodeWithText("Send").fetchSemanticsNode()
        compose.onAllNodesWithText("Your Watchlist Is Empty").assertCountEquals(0)
        compose.onAllNodesWithText("WATCHLIST · 0").assertCountEquals(0)
    }

    @Test
    fun theHeaderAddAndTheDashedRowBothOpenTheAdd() {
        home(listOf(row("Bitcoin", "BTC")))
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Add to Watchlist").performClick()
        assertEquals(2, added)
    }

    @Test
    fun aHyphenInANameLetsTheLineBreakAfterIt() {
        home(listOf(row("Ondo Short-Term U.S. Government Bond Fund", "OUSG")))
        val layout = compose.onNodeWithText("Ondo", substring = true, useUnmergedTree = true).layout()
        val text = layout.layoutInput.text.text
        val first = text.substring(layout.getLineStart(0), layout.getLineEnd(0)).replace("\u200B", "").trim()
        assertEquals("the first line ends after the hyphen, as the mock's does", "Ondo Short-", first)
    }

    @Test
    fun sendIsSetInTheCapsuleLabelSize() {
        home(null)
        assertEquals(15.5.sp, compose.onNodeWithText("Send", useUnmergedTree = true).layout().layoutInput.style.fontSize)
    }

    @Test
    fun theFootnoteKeepsThirtyTwoDpBelowIt() {
        val footnote = "Prices from CoinGecko at 12:30, change over 24 hours."
        home(listOf(row("Bitcoin", "BTC")), footnote = footnote)
        val below = compose.onNodeWithTag("home").getUnclippedBoundsInRoot().bottom - compose.onNodeWithText(footnote).getUnclippedBoundsInRoot().bottom
        assertEquals("the space under the footnote, as the mock's end frame has it", 32f, below.value, 0.5f)
    }

    @Test
    fun theEmptyStateActionsStartNineteenDpUnderItsText() {
        home(emptyList())
        val body = compose.onNodeWithText("Add any coin, token or fund", substring = true).getUnclippedBoundsInRoot()
        val add = compose.onNodeWithText("Add to Watchlist").getUnclippedBoundsInRoot()
        assertEquals("the space between the body and the first action", 19f, (add.top - body.bottom).value, 0.5f)
    }

    @Test
    fun theChartIsCentredOnTheRowsTwoLines() {
        home(listOf(row("Bitcoin", "BTC")))
        val name = compose.onNodeWithText("Bitcoin", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val ticker = compose.onNodeWithText("BTC", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val chart = compose.onNodeWithTag(CHART_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val lines = (name.top + ticker.bottom) / 2
        val centre = (chart.top + chart.bottom) / 2
        assertEquals("the chart's centre is the row's centre, as the mock's is, not above it", lines.value, centre.value, 0.6f)
    }

    @Test
    fun theEmptyStatePlusSitsOneDpLowerThanTheKitCentresIt() {
        home(emptyList())
        val label = compose.onNodeWithText("Add to Watchlist").getUnclippedBoundsInRoot()
        val plus = compose.onNode(hasTestTag(TEXT_ACTION_ICON_TAG) and hasAnySibling(hasText("Add to Watchlist")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val drop = (plus.top + plus.bottom) / 2 - (label.top + label.bottom) / 2
        // At this density the kit centres the 18 dp plus 0.5 dp below the label's centre; the mock's empty frame sets it 1 dp lower still.
        assertEquals("the plus sits 1 dp lower than the kit centres it, as the mock's empty frame has it", 1.5f, drop.value, 0.4f)
    }
}
