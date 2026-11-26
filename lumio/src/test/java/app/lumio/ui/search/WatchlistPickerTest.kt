package app.lumio.ui.search

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.airvault.core.model.ChainId
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.ui.home.AssetIconSpec
import app.lumio.watchlist.WatchedAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rules of the Add to Watchlist row: the star shows whether the asset is watched, the rank follows the name, and the line under it is the ticker. */
@RunWith(RobolectricTestRunner::class)
class WatchlistPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val bitcoin = WatchedAsset(NetworkId(ChainId("bip122:000000000019d6689c085ae165831e93"), testnet = false))
    private val solana = WatchedAsset(NetworkId(ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"), testnet = false))

    private fun row(name: String, ticker: String, rank: MarketRank? = null, sameName: Int = 0) =
        PickerRow(asset = bitcoin, name = name, ticker = ticker, icon = AssetIconSpec.Bitcoin, watched = false, rank = rank, sameName = sameName)

    @Test
    fun theStarIsFilledForAWatchedAssetAndEmptyForTheOthers() {
        val toggled = mutableListOf<WatchedAsset>()
        compose.setContent {
            ClearAirTheme {
                WatchlistPicker(
                    query = "",
                    onQuery = {},
                    label = "Coins",
                    rows = listOf(
                        PickerRow(bitcoin, "Bitcoin", "BTC", AssetIconSpec.Bitcoin, watched = true),
                        PickerRow(solana, "Solana", "SOL", AssetIconSpec.Solana, watched = false),
                    ),
                    onToggle = { toggled += it },
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithContentDescription("On your watchlist").assertIsOn()
        compose.onNodeWithContentDescription("Add to watchlist").assertIsOff().performClick()
        assertEquals(listOf(solana), toggled)
    }

    @Test
    fun theRankFollowsTheName() {
        compose.setContent {
            ClearAirTheme {
                WatchlistPicker(
                    query = "",
                    onQuery = {},
                    label = "Coins",
                    rows = listOf(row("Department Of Government Efficiency", "DOGE", MarketRank.Place(4004))),
                    onToggle = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Department Of Government Efficiency #4,004").assertExists()
    }

    @Test
    fun aRankIsGroupedByThousandsAndAnUnrankedAssetHasNone() {
        assertEquals("#1", rankText(MarketRank.Place(1)))
        assertEquals("#14,812", rankText(MarketRank.Place(14_812)))
        assertNull(rankText(MarketRank.Unranked))
        assertNull(rankText(null))
    }

    @Test
    fun theSecondLineIsTheTicker() {
        assertEquals("BTC", pickerDetail(row("Bitcoin", "BTC", MarketRank.Place(1))))
    }

    @Test
    fun aTickerThatReadsAsTheNameIsLeftOut() {
        assertNull(pickerDetail(row("BNB", "BNB", MarketRank.Place(4))))
        assertNull(pickerDetail(row("XRP", "xrp")))
    }

    @Test
    fun theLineSaysHowManyTokensShareTheNameAndWhenTheAssetIsUnranked() {
        assertEquals("DOGE · 4 more tokens share this name", pickerDetail(row("Department Of Government Efficiency", "DOGE", sameName = 4)))
        assertEquals("DOGE · 1 more token shares this name", pickerDetail(row("Department Of Government Efficiency", "DOGE", sameName = 1)))
        assertEquals("BCSBGC3 · Unranked", pickerDetail(row("Backed Swiss Domestic Government Bond 0-3", "BCSBGC3", MarketRank.Unranked)))
    }
}
