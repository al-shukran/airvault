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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.chains.NATIVE_COINS
import app.lumio.data.asset.AssetId
import app.lumio.data.config.Fiat
import app.lumio.data.WallClock
import app.lumio.data.net.Networks
import app.lumio.data.price.Kept
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.MarketQuote
import app.lumio.home.HomeAsset
import app.lumio.network.NetworkMode
import app.lumio.receive.ReceiveAnswers
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.MarketHero
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.home.compactUsd
import app.lumio.ui.home.freshnessOf
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.receive.ReceiveFlowSheet
import app.lumio.ui.receive.receiveCoinsOf
import app.lumio.ui.wallets.AssetRows
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
 * asks the sources again. The active wallet is the one the profile panel chose, or the first paired wallet. Send opens the Recipient step of the send
 * from the wallet's first account that holds some coin, and Receive opens the Receive sheet over Home, which asks for the asset first.
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
    val wallet = view?.wallets?.active(selection) { it.id }
    val testnet = selection.mode == NetworkMode.TESTNET
    val watchlist = wallet?.let { board.watchlists[it.id] }
    val sort = watchlist?.sort ?: WatchlistSort.MARKET_CAP
    val nowMs by produceState(WallClock.nowMs()) {
        while (true) {
            delay(FRESHNESS_TICK_MS)
            value = WallClock.nowMs()
        }
    }
    val fiat = LocalFiat.current
    val rows = watchlist?.ordered { board.prices[it.assetId]?.change24hPct }.orEmpty().map { asset ->
        rowOf(asset, view, wallet?.id, board.prices[asset.assetId], board.dayChartOf(asset.assetId), testnet, fiat)
    }
    var sortOpen by remember { mutableStateOf(false) }
    var receiveOpen by remember { mutableStateOf(false) }
    var receiveOpening by remember { mutableIntStateOf(0) }
    val accounts = if (view != null && wallet != null) view.accountsOf(wallet.id) else emptyList()
    val hero = heroOf(board.market, testnet, nowMs, fiat, ticks = marketTicks(watchlist?.assets.orEmpty(), board::dayChartOf))
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
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = HOME_TOP, bottom = ClearAirSpacing.md)) {
                HomeScreen(
                    hero = hero,
                    rows = rows,
                    sort = sort,
                    testnet = testnet,
                    onSend = { sendSourceOf(accounts)?.let { navController.navigate(sendRoute(it, selection.mode)) } },
                    onReceive = {
                        receiveOpening++
                        receiveOpen = true
                    },
                    onSort = { sortOpen = true },
                    onOpen = { asset -> wallet?.let { navController.navigate(LumioRoute.coin(it.id, asset)) } },
                )
            }
            ReceiveFlowSheet(
                visible = receiveOpen,
                opening = receiveOpening,
                start = ReceiveAnswers(),
                assets = receiveCoinsOf(accounts, view?.wallets.orEmpty()) { coin -> AssetRows.nativeIcon(coin.network, coin.symbol, app.registry::network) },
                onClose = { receiveOpen = false },
            )
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

/** The space above the hero's eyebrow, under the top bar. */
private val HOME_TOP = 10.dp

/**
 * The account that Home's Send sends from: the first one that holds some of its coin, else the first one. Null when the wallet has no account on the
 * side, and Send then does nothing.
 */
private fun sendSourceOf(accounts: List<WalletAccount>): WalletAccount? =
    accounts.firstOrNull { (it.balance?.balance?.signum() ?: 0) > 0 } ?: accounts.firstOrNull()

/**
 * The points of the hero's chart. The sources give no history of the whole market's total, so the chart is the day of Bitcoin, which leads the market,
 * when the watchlist watches it and its chart has landed. Without it the hero has no chart.
 */
private fun marketTicks(watched: List<WatchedAsset>, dayChartOf: (AssetId) -> PriceSeries?): List<Double> =
    watched.firstOrNull { coinNames(it).first == "BTC" }?.let { dayChartOf(it.assetId) }?.points?.map { it.usd }.orEmpty()

/** The hero as Home shows it: the market's total in dollars, its 24 h change, and when the figure was read. A test network shows no market figure. */
private fun heroOf(market: Kept<MarketQuote>?, testnet: Boolean, nowMs: Long, fiat: Fiat, ticks: List<Double>): MarketHero {
    if (testnet || market == null) return MarketHero(total = null, change24hPct = null, freshness = null)
    val quote = market.value
    return MarketHero(
        total = compactUsd(quote.totalUsd, fiat),
        change24hPct = quote.change24hPct,
        freshness = freshnessOf(quote.asOfMs, market.stale, nowMs),
        ticks = ticks,
    )
}

/**
 * One watched asset as its row: its name and symbol, what the wallet holds of it, its price and change, and the ticks of its day's prices. A test network
 * shows no price and no chart.
 */
private fun rowOf(
    asset: WatchedAsset,
    view: WalletsView?,
    walletId: String?,
    price: Price?,
    day: PriceSeries?,
    testnet: Boolean,
    fiat: Fiat,
): WatchlistRow {
    val (symbol, name) = coinNames(asset)
    val held = if (view != null && walletId != null) heldOf(asset, view.accountsOf(walletId)) else null
    return WatchlistRow(
        asset = asset,
        name = name,
        detail = if (held == null) symbol else "$symbol · ${held.stripTrailingZeros().toPlainString()} held",
        priceUsd = if (testnet || price == null) null else fiat.amount(price.usd),
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
