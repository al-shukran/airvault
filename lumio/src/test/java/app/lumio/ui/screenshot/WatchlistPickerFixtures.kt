package app.lumio.ui.screenshot

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lumio.data.net.NetworkId
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.search.MarketRank
import app.lumio.ui.search.PickerLogo
import app.lumio.ui.search.PickerRow
import app.lumio.ui.search.WatchlistPicker
import app.lumio.watchlist.WatchedAsset
import java.io.File

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
