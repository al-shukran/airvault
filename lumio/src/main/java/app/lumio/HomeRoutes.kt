package app.lumio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.chains.NATIVE_COINS
import app.lumio.data.WallClock
import app.lumio.data.net.Networks
import app.lumio.data.price.Kept
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.MarketQuote
import app.lumio.home.HomeAsset
import app.lumio.network.NetworkMode
import app.lumio.ui.home.FIAT
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.MarketHero
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.home.compactUsd
import app.lumio.ui.home.freshnessOf
import app.lumio.ui.kit.LumioTab
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletsView
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import app.lumio.watchlist.ordered
import java.math.BigDecimal
import kotlinx.coroutines.delay

/** How often the freshness line counts its age again, so "8 s ago" keeps counting while Home is open. */
private const val FRESHNESS_TICK_MS = 15_000L

/**
 * The Home tab: the total market cap, and the watchlist of the active wallet with each asset's price, its 24 h change and its day's tick chart. The
 * market figures, the prices and the charts come from the open vault's shared board, so Home shows the last ones kept on its first frame, and each visit
 * asks the sources again. The active wallet is the first paired wallet until the profile panel can choose one. Send and Receive open the wallet's page,
 * where each account has its own.
 */
@Composable
internal fun HomeRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    LaunchedEffect(board) { board.refreshMarket() }
    val view = board.view
    val wallet = view?.wallets?.firstOrNull()
    val testnet = selection.mode == NetworkMode.TESTNET
    val watchlist = wallet?.let { board.watchlists[it.id] }
    val sort = watchlist?.sort ?: WatchlistSort.MARKET_CAP
    val nowMs by produceState(WallClock.nowMs()) {
        while (true) {
            delay(FRESHNESS_TICK_MS)
            value = WallClock.nowMs()
        }
    }
    val rows = watchlist?.ordered { board.prices[it.assetId]?.change24hPct }.orEmpty().map { asset ->
        rowOf(asset, view, wallet?.id, board.prices[asset.assetId], board.dayChartOf(asset.assetId), testnet)
    }
    var sortOpen by remember { mutableStateOf(false) }
    val hero = heroOf(board.market, testnet, nowMs)
    TabRoute(
        tab = LumioTab.HOME,
        navController = navController,
        holder = holder,
        selection = selection,
        listVersion = listVersion,
        onLock = onLock,
        title = wallet?.name ?: LumioTab.HOME.label,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = ClearAirSpacing.md)) {
                HomeScreen(
                    hero = hero,
                    rows = rows,
                    sort = sort,
                    testnet = testnet,
                    onSend = { wallet?.let { navController.navigate(LumioRoute.wallet(it.id)) } },
                    onReceive = { wallet?.let { navController.navigate(LumioRoute.wallet(it.id)) } },
                    onSort = { sortOpen = true },
                    onOpen = { asset -> wallet?.let { navController.navigate(LumioRoute.coin(it.id, asset)) } },
                )
            }
            WatchlistSortSheet(
                visible = sortOpen,
                selected = sort,
                onSelect = { next ->
                    sortOpen = false
                    watchlist?.let { list -> wallet?.let { board.putWatchlist(it.id, list.copy(sort = next)) } }
                },
                onClose = { sortOpen = false },
            )
        }
    }
}

/** The hero as Home shows it: the market's total in dollars, its 24 h change, and when the figure was read. A test network shows no market figure. */
private fun heroOf(market: Kept<MarketQuote>?, testnet: Boolean, nowMs: Long): MarketHero {
    if (testnet || market == null) return MarketHero(total = null, change24hPct = null, freshness = null)
    val quote = market.value
    return MarketHero(
        total = compactUsd(quote.totalUsd),
        change24hPct = quote.change24hPct,
        freshness = freshnessOf(quote.asOfMs, market.stale, nowMs),
    )
}

/**
 * One watched asset as its row: its name and symbol, what the wallet holds of it, its price and change, and the ticks of its day's prices. A test network
 * shows no price and no chart.
 */
private fun rowOf(asset: WatchedAsset, view: WalletsView?, walletId: String?, price: Price?, day: PriceSeries?, testnet: Boolean): WatchlistRow {
    val (symbol, name) = coinNames(asset)
    val held = if (view != null && walletId != null) heldOf(asset, view.accountsOf(walletId)) else null
    return WatchlistRow(
        asset = asset,
        name = name,
        detail = if (held == null) symbol else "$symbol · ${held.stripTrailingZeros().toPlainString()} held",
        priceUsd = if (testnet || price == null) null else FIAT.format(price.usd),
        change24hPct = if (testnet) null else price?.change24hPct,
        ticks = if (testnet) emptyList() else day?.points?.map { it.usd }.orEmpty(),
    )
}

/** The native amount that [accounts] hold of [asset], or null when they hold none. Token amounts are not read on the account rows. */
private fun heldOf(asset: WatchedAsset, accounts: List<WalletAccount>): BigDecimal? = accounts
    .mapNotNull { it.balance }
    .filter { it.assetId == asset.assetId }
    .map(HomeAsset::balance)
    .reduceOrNull(BigDecimal::add)
    ?.takeIf { it.signum() > 0 }

/** The symbol and the name of a watched asset: the coin's own, or a token's grouped contract, which no name is read for. */
internal fun coinNames(asset: WatchedAsset): Pair<String, String> {
    asset.contract?.let { return groupAddress(it) to groupAddress(it) }
    NATIVE_COINS.firstOrNull { it.network == asset.network }?.let { return it.symbol to it.name }
    return when {
        asset.network.isBtc -> "BTC" to "Bitcoin"
        asset.network.isEvm && Networks.evmIdOf(asset.network.chain) == 1L -> "ETH" to "Ethereum"
        else -> asset.network.name to asset.network.name
    }
}
