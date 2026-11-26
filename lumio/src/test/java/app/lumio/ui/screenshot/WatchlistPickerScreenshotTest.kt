package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lumio.data.net.NetworkId
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.search.MarketRank
import app.lumio.ui.search.PickerRow
import app.lumio.ui.search.WatchlistPicker
import app.lumio.watchlist.WatchedAsset
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of Add to Watchlist, light and dark: the list before a query and the results of "government". They are drawn at the mock's 411 × 891 dp
 * phone, under a 40 dp status bar, with the mock's rows, so each golden lays over its frame of the approved mock. The rows are fixture data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistPickerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun pickerLight() = compose.clearAirSnapshot("watchlist_picker_light", PHONE) { Page(query = "", label = "Top by Market Cap", rows = topRows()) }

    @Test
    fun pickerDark() = compose.clearAirSnapshot("watchlist_picker_dark", PHONE) { Page(query = "", label = "Top by Market Cap", rows = topRows()) }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun searchLight() = compose.clearAirSnapshot("watchlist_picker_search_light", PHONE) { Page(query = "government", label = "Results · 6", rows = searchRows()) }

    @Test
    fun searchDark() = compose.clearAirSnapshot("watchlist_picker_search_dark", PHONE) { Page(query = "government", label = "Results · 6", rows = searchRows()) }

    @androidx.compose.runtime.Composable
    private fun Page(query: String, label: String, rows: List<PickerRow>) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(STATUS_BAR))
            WatchlistPicker(query = query, onQuery = {}, label = label, rows = rows, onToggle = {}, onBack = {}, onLock = {})
        }
    }

    private val registry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets").map { File(it, path) }.firstOrNull(File::isFile)?.readText()
    }

    private fun token(chainId: Long, symbol: String): AssetIconSpec =
        AssetIconSpec.Token(registry.tokens().first { it.chainId == chainId && it.symbol == symbol }, registry.network(chainId))

    private fun chain(chainId: Long): AssetIconSpec = AssetIconSpec.Chain(registry.network(chainId))

    private var serial = 0

    private fun row(name: String, ticker: String, icon: AssetIconSpec, watched: Boolean, rank: MarketRank?, sameName: Int = 0) = PickerRow(
        asset = WatchedAsset(NetworkId(ChainId("eip155:1"), testnet = false), contract = "0x" + (serial++).toString().padStart(40, '0')),
        name = name,
        ticker = ticker,
        icon = icon,
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
        row("XRP", "XRP", AssetIconSpec.Letter("X"), watched = false, rank = MarketRank.Place(6)),
        row("Solana", "SOL", AssetIconSpec.Solana, watched = true, rank = MarketRank.Place(7)),
        row("Figure Heloc", "FIGR_HELOC", AssetIconSpec.Letter("F"), watched = false, rank = MarketRank.Place(8)),
        row("TRON", "TRX", AssetIconSpec.Letter("T"), watched = false, rank = MarketRank.Place(9)),
        row("Hyperliquid", "HYPE", chain(999), watched = false, rank = MarketRank.Place(10)),
        row("Zcash", "ZEC", AssetIconSpec.Letter("Z"), watched = false, rank = MarketRank.Place(11)),
        row("Monero", "XMR", AssetIconSpec.Letter("M"), watched = false, rank = MarketRank.Place(12)),
    )

    private fun searchRows(): List<PickerRow> = listOf(
        row("Invesco Short Duration US Government Securities Fund", "USTB", AssetIconSpec.Letter("U"), watched = true, rank = MarketRank.Place(85)),
        row("Ondo Short-Term U.S. Government Bond Fund", "OUSG", AssetIconSpec.Letter("O"), watched = true, rank = MarketRank.Place(120)),
        row("Department Of Government Efficiency", "DOGE", AssetIconSpec.Letter("D"), watched = false, rank = MarketRank.Place(4004), sameName = 4),
        row("Deer Seized by US Government", "BABY", AssetIconSpec.Letter("B"), watched = false, rank = MarketRank.Place(9481)),
        row("iShares Global Government Bond USD Hedged Active ETF (Ondo Tokenized)", "GGOVON", AssetIconSpec.Letter("G"), watched = false, rank = MarketRank.Place(14_812)),
        row("Backed Swiss Domestic Government Bond 0-3", "BCSBGC3", AssetIconSpec.Letter("B"), watched = false, rank = MarketRank.Unranked),
    )

    private companion object {
        val PHONE = DpSize(411.dp, 891.dp)
        val STATUS_BAR = 40.dp
    }
}
