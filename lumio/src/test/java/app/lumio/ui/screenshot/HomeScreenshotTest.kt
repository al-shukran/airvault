package app.lumio.ui.screenshot

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.network.NetworkMode
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.kit.ClearAirDisc
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.network.PanelWallet
import app.lumio.ui.shell.LumioRootScreen
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Home tab, in the frames of the approved mock: its twelve assets at 411 dp, scrolled to the top and to the end, light and dark; the
 * longest names at 360 dp; the empty watchlist; the test side; and the sort sheet. Each frame is the whole root screen with its top bar and tab bar, and
 * the mock's own logos and prices, so a golden is compared with the mock's PNG band by band. Recorded on the Linux runner, with reduced motion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeLight() = frame("home_light") { Home(rows = mockRows()) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun homeDark() = frame("home_dark") { Home(rows = mockRows()) }

    @Test
    fun homeEndLight() = frame("home_end_light") { Home(rows = mockRows(), end = true) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun homeEndDark() = frame("home_end_dark") { Home(rows = mockRows(), end = true) }

    @Test
    fun emptyLight() = frame("home_empty_light") { Home(rows = emptyList()) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun emptyDark() = frame("home_empty_dark") { Home(rows = emptyList()) }

    @Test
    fun narrowLight() = frame("home_narrow_360_light", width = NARROW) { Home(rows = mockRows()) }

    @Test
    fun narrowEndLight() = frame("home_narrow_360_end_light", width = NARROW) { Home(rows = mockRows(), end = true) }

    @Test
    fun fallLight() = frame("home_fall_light") { Home(rows = fallingRows()) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun fallDark() = frame("home_fall_dark") { Home(rows = fallingRows()) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun testnet() = frame("home_testnet") { Home(rows = mockRows(), testnet = true) }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun sortSheet() = frame("home_sort_sheet") {
        Box(modifier = Modifier.fillMaxSize()) {
            Home(rows = mockRows())
            WatchlistSortSheet(visible = true, selected = WatchlistSort.CHANGE_24H, onSelect = {}, onClose = {})
        }
    }

    /** A golden of [content] in the mock's frame, with the mock's 24 dp gesture area under the tab bar, which a phone's navigation inset gives it. */
    private fun frame(name: String, width: Int = WIDE, content: @Composable () -> Unit) =
        compose.lumioSnapshot(name = name, frame = DpSize(width.dp, HEIGHT.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                Spacer(Modifier.height(GESTURE_AREA.dp))
            }
        }

    /** The root screen as the mock draws it: "Air 1" in the top bar, Home's body, and the tab bar. [end] scrolls the body to its end. */
    @Composable
    private fun Home(rows: List<WatchlistRow>, end: Boolean = false, testnet: Boolean = false) {
        LumioRootScreen(
            tab = LumioTab.HOME,
            title = "Air 1",
            mode = if (testnet) NetworkMode.TESTNET else NetworkMode.MAINNET,
            onMode = {},
            panelWallets = listOf(PanelWallet(id = "air-1", name = "Air 1", detail = "", tone = WalletEmblemTone.BLUE)),
            activeWalletId = "air-1",
            onWallet = {},
            testNetworks = 0,
            networkSentence = "",
            offline = null,
            onTab = {},
            onScan = {},
            onTestNetworks = {},
            onPair = {},
            onLock = {},
            search = { _ -> },
        ) {
            val scroll = rememberScrollState()
            if (end) LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll)) {
                HomeScreen(
                    rows = rows,
                    sort = WatchlistSort.MARKET_CAP,
                    testnet = testnet,
                    footnote = if (testnet) null else MOCK_FOOTNOTE,
                    onSend = {},
                    onReceive = {},
                    onSort = {},
                    onAdd = {},
                    onRestore = {},
                    onOpen = {},
                    logo = { row -> MockLogo(MOCK_WATCHLIST.first { it.name == row.name }) },
                )
            }
        }
    }

    /** A logo of the mock, set in the disc as the mock sets it: at its own size and scale, on its own fill when it has one. */
    @Composable
    private fun MockLogo(row: FixtureRow) {
        val bitmap = checkNotNull(javaClass.classLoader?.getResourceAsStream("home-mock/logo_${row.logo}.png")).use { BitmapFactory.decodeStream(it) }
        ClearAirDisc {
            val box = Modifier.size(row.logoDp.dp)
            Box(
                modifier = row.logoFill?.let { box.clip(CircleShape).background(Color(it)) } ?: box,
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.requiredSize((row.logoDp * row.logoScale).dp),
                )
            }
        }
    }

    private fun mockRows(): List<WatchlistRow> = MOCK_WATCHLIST.mapIndexed { index, row ->
        WatchlistRow(
            asset = WatchedAsset(NetworkId(ChainId("eip155:1"), testnet = false), contract = "0x$index"),
            name = row.name,
            ticker = row.ticker,
            icon = AssetIconSpec.Letter(row.ticker.take(1)),
            priceUsd = row.price,
            change24hPct = row.change,
            ticks = row.ticks,
        )
    }

    /** The mock's rows with Bitcoin, Ethereum and Solana falling: their day turned over and their change made negative, so the rows show red ▼. */
    private fun fallingRows(): List<WatchlistRow> = mockRows().map { row ->
        if (row.name in setOf("Bitcoin", "Ethereum", "Solana")) row.copy(change24hPct = -(row.change24hPct ?: 0.0), ticks = row.ticks.map { 2 * row.ticks.last() - it }) else row
    }

    private companion object {
        /** The mock's frames: 411 dp and 360 dp wide, 891 dp tall less its 40 dp status bar, which a golden has no part of. */
        const val WIDE = 411
        const val NARROW = 360
        const val HEIGHT = 851
        const val GESTURE_AREA = 24

        /** The mock's footnote, word for word, so the end frames scroll as far as the mock's. */
        const val MOCK_FOOTNOTE = "Prices from CoinGecko at 12:30, change over 24 hours. eurSAFO is priced in US dollars at 1.0935 USD per euro (ECB, 25 Nov 2026)."
    }
}
