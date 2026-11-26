package app.lumio.ui.screenshot

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.search.MarketRank
import app.lumio.ui.search.PICKER_ROW_TAG
import app.lumio.ui.search.PickerLogo
import app.lumio.ui.search.PickerRow
import app.lumio.ui.search.WatchlistPicker
import app.lumio.watchlist.WatchedAsset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Add to Watchlist, light and dark: the list before a query and the results of "government". They are drawn at the mock's 411 × 891 dp
 * phone, under a 40 dp status bar, with the mock's rows and logos, so each golden lays over its frame of the approved mock. The rows are fixture data
 * only: the live list keeps only the coins Lumio can watch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistPickerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pickerLight() = compose.clearAirSnapshot("watchlist_picker_light", PickerFixtures.PHONE) { PickerFixtures.Top() }

    @Test
    fun pickerDark() = compose.clearAirSnapshot("watchlist_picker_dark", PickerFixtures.PHONE) { PickerFixtures.Top() }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun searchLight() = compose.clearAirSnapshot("watchlist_picker_search_light", PickerFixtures.PHONE) { PickerFixtures.Search() }

    @Test
    fun searchDark() = compose.clearAirSnapshot("watchlist_picker_search_dark", PickerFixtures.PHONE) { PickerFixtures.Search() }
}

/**
 * The rows of Add to Watchlist sit on the mock's grid, measured in dp at the mock's own scale of 2 px a dp, where no length rounds: the first row starts
 * at 182 dp, a one-line row is 62 dp, a two-line name that ends in its rank makes 74 dp, three lines 94 dp, and a two-line name with no rank 73 dp. The
 * goldens are drawn at 1.75 px a dp, where each row rounds to whole pixels, so their dividers drift up to 0.6 dp from the mock's with the same layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistPickerGridTest {
    private val PHONE_TAG = "phone"


    @get:Rule
    val compose = createComposeRule()

    /** The top and the height of each row, in dp, drawn at 2 px a dp. */
    private fun rows(content: @Composable () -> Unit): List<Pair<Float, Float>> {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                ClearAirTheme { Box(Modifier.requiredSize(PickerFixtures.PHONE).testTag(PHONE_TAG)) { content() } }
            }
        }
        // The phone is larger than the test window, which centres it, so each top is read from the phone's own top.
        val phone = compose.onAllNodesWithTag(PHONE_TAG).fetchSemanticsNodes().single().boundsInRoot.top
        return compose.onAllNodesWithTag(PICKER_ROW_TAG).fetchSemanticsNodes().map { (it.boundsInRoot.top - phone) / 2f to it.boundsInRoot.height / 2f }
    }

    @Test
    fun theTopListStartsAt182AndEachRowIs62() {
        // The last row runs off the bottom of the phone, so only the rows wholly on it are measured.
        val rows = rows { PickerFixtures.Top() }.filter { (top, height) -> top + height < PickerFixtures.PHONE.height.value }

        assertEquals(182f, rows.first().first)
        assertEquals(List(rows.size) { 62f }, rows.map { it.second })
    }

    @Test
    fun aResultGrowsWithItsNameAndARankedLastLineIsOneDpTaller() {
        val rows = rows { PickerFixtures.Search() }

        assertEquals(182f, rows.first().first)
        assertEquals(listOf(74f, 74f, 74f, 74f, 94f, 73f), rows.map { it.second })
    }
}

/** The mock's rows and logos, as fixture data for the goldens and the grid test. */
internal object PickerFixtures {
    val PHONE = DpSize(411.dp, 891.dp)
    private val STATUS_BAR = 40.dp
    private val LOGOS = listOf("src/debug/resources/demo/logos", "lumio/src/debug/resources/demo/logos").map(::File).first { it.isDirectory }

    @Composable
    fun Top() = Page(query = "", label = "Top by Market Cap", rows = topRows())

    @Composable
    fun Search() = Page(query = "government", label = "Results · 6", rows = searchRows())

    @Composable
    private fun Page(query: String, label: String, rows: List<PickerRow>) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(STATUS_BAR))
            WatchlistPicker(query = query, onQuery = {}, label = label, rows = rows, onToggle = {}, onBack = {}, onLock = {}, image = { logo(it) })
        }
    }

    private val registry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets").map { File(it, path) }.firstOrNull(File::isFile)?.readText()
    }

    private fun token(chainId: Long, symbol: String): AssetIconSpec =
        AssetIconSpec.Token(registry.tokens().first { it.chainId == chainId && it.symbol == symbol }, registry.network(chainId))

    private fun chain(chainId: Long): AssetIconSpec = AssetIconSpec.Chain(registry.network(chainId))

    /** The mock's own logo of a coin, which the demo market serves from the debug resources as `demo:{id}`. */
    private fun logo(url: String): ImageBitmap? = File(LOGOS, url.removePrefix("demo:") + ".png").takeIf(File::isFile)?.readBytes()
        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

    private fun remote(id: String): PickerLogo = PickerLogo.Remote("demo:$id")

    private fun row(name: String, ticker: String, icon: AssetIconSpec, watched: Boolean, rank: MarketRank?) =
        row(name, ticker, PickerLogo.Mark(icon), watched, rank)

    private fun row(name: String, ticker: String, logo: PickerLogo, watched: Boolean, rank: MarketRank?, sameName: Int = 0) = PickerRow(
        asset = WatchedAsset(NetworkId(ChainId("eip155:1"), testnet = false), contract = "0x" + name.hashCode().toUInt().toString(16).padStart(40, '0')),
        name = name,
        ticker = ticker,
        logo = logo,
        watched = watched,
        rank = rank,
        sameName = sameName,
    )

    private fun topRows(): List<PickerRow> = listOf(
        row("Bitcoin", "BTC", AssetIconSpec.Bitcoin, watched = true, rank = MarketRank.Place(1)),
        row("Ethereum", "ETH", chain(1), watched = true, rank = MarketRank.Place(2)),
        row("Tether", "USDT", token(1, "USDT"), watched = true, rank = MarketRank.Place(3)),
        row("BNB", "BNB", chain(56), watched = true, rank = MarketRank.Place(4)),
        row("USD Coin", "USDC", token(1, "USDC"), watched = true, rank = MarketRank.Place(5)),
        row("XRP", "XRP", remote("ripple"), watched = false, rank = MarketRank.Place(6)),
        row("Solana", "SOL", AssetIconSpec.Solana, watched = true, rank = MarketRank.Place(7)),
        row("Figure Heloc", "FIGR_HELOC", remote("figure-heloc"), watched = false, rank = MarketRank.Place(8)),
        row("TRON", "TRX", remote("tron"), watched = false, rank = MarketRank.Place(9)),
        row("Hyperliquid", "HYPE", chain(999), watched = false, rank = MarketRank.Place(10)),
        row("Zcash", "ZEC", remote("zcash"), watched = false, rank = MarketRank.Place(11)),
        row("Monero", "XMR", remote("monero"), watched = false, rank = MarketRank.Place(12)),
    )

    private fun searchRows(): List<PickerRow> = listOf(
        row("Invesco Short Duration US Government Securities Fund", "USTB", remote("superstate-short-duration-us-government-securities-fund-ustb"), true, MarketRank.Place(85)),
        row("Ondo Short-Term U.S. Government Bond Fund", "OUSG", remote("ousg"), true, MarketRank.Place(120)),
        row("Department Of Government Efficiency", "DOGE", remote("department-of-government-efficiency"), false, MarketRank.Place(4004), sameName = 4),
        row("Deer Seized by US Government", "BABY", remote("deer-seized-by-us-government"), false, MarketRank.Place(9481)),
        row("iShares Global Government Bond USD Hedged Active ETF (Ondo Tokenized)", "GGOVON", remote("ishares-global-government-bond-usd-hedged-active-etf-ondo-tokenized"), false, MarketRank.Place(14_812)),
        row("Backed Swiss Domestic Government Bond 0-3", "BCSBGC3", remote("backed-swiss-domestic-government-bond-0-3"), false, MarketRank.Unranked),
    )
}
