package app.lumio.ui.home

import androidx.compose.foundation.layout.Column
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

    private fun home(rows: List<WatchlistRow>) = compose.setContent {
        ClearAirTheme {
            Column {
                HomeScreen(
                    rows = rows,
                    sort = WatchlistSort.MARKET_CAP,
                    testnet = false,
                    footnote = null,
                    onSend = {},
                    onReceive = {},
                    onSort = {},
                    onAdd = { added++ },
                    onRestore = {},
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
    fun theHeaderAddAndTheDashedRowBothOpenTheAdd() {
        home(listOf(row("Bitcoin", "BTC")))
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Add to Watchlist").performClick()
        assertEquals(2, added)
    }
}
