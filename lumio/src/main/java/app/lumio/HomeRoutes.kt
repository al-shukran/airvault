package app.lumio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.components.groupAddress
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lumio.chains.NATIVE_COINS
import app.lumio.data.WallClock
import app.lumio.data.config.Fiat
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.network.NetworkMode
import app.lumio.receive.ReceiveAnswers
import app.lumio.tokens.TokenHolding
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.receive.ReceiveFlowSheet
import app.lumio.ui.receive.receiveCoinsOf
import app.lumio.ui.send.clockOf
import app.lumio.ui.wallets.AssetRows
import app.lumio.wallets.WalletAccount
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WatchlistSort
import app.lumio.watchlist.ordered
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The Home tab: Send and Receive, and the watchlist of the active wallet with each asset's price, its 24 h change and its day's tick chart. The prices and
 * the charts come from the open vault's shared board, so Home shows the last ones kept on its first frame, and each visit asks the sources again. The
 * active wallet is the one the profile panel chose, or the first paired wallet. Send opens the Recipient step of the send from the wallet's first account
 * that holds some coin, and Receive opens the Receive sheet over Home, which asks for the asset first. Add opens the Add to Watchlist page.
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
    LaunchedEffect(board) { board.refreshPrices() }
    val scope = rememberCoroutineScope()
    val includeSpam = rememberTransactions(app).activityIncludesSpam
    val view = board.view
    val wallet = view?.wallets?.active(selection) { it.id }
    val testnet = selection.mode == NetworkMode.TESTNET
    val watchlist = wallet?.let { board.watchlists[it.id] }
    val sort = watchlist?.sort ?: WatchlistSort.MARKET_CAP
    val fiat = LocalFiat.current
    val watched = watchlist?.ordered { board.prices[it.assetId]?.change24hPct }
    val rows = watched?.map { asset -> rowOf(asset, board.tokenHeld, board.prices[asset.assetId], board.dayChartOf(asset.assetId), testnet, fiat, app) }
    val footnote = if (testnet) null else footnoteOf(watched.orEmpty().mapNotNull { board.prices[it.assetId]?.asOfMs }.maxOrNull(), WallClock.nowMs())
    var sortOpen by remember { mutableStateOf(DebugDemoData.flag("sort")) }
    var receiveOpen by remember { mutableStateOf(false) }
    var receiveOpening by remember { mutableIntStateOf(0) }
    val accounts = if (view != null && wallet != null) view.accountsOf(wallet.id) else emptyList()
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
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HomeScreen(
                    rows = rows,
                    sort = sort,
                    testnet = testnet,
                    footnote = footnote,
                    onSend = { sendSourceOf(accounts)?.let { navController.navigate(sendRoute(it, selection.mode)) } },
                    onReceive = {
                        receiveOpening++
                        receiveOpen = true
                    },
                    onSort = { sortOpen = true },
                    onAdd = { navController.navigate(LumioRoute.WATCHLIST_ADD) },
                    onRestore = wallet?.let { active -> { board.changeWatchlist(active.id) { list -> list.copy(assets = FIRST_RUN_ASSETS) } } },
                    onOpen = { asset ->
                        wallet?.let {
                            scope.launch {
                                val vault = app.lumioVault()
                                if (vault != null) CoinActivityPreload.preload(app, vault, CoinActivityKey(asset, selection.mode, includeSpam))
                                navController.navigate(LumioRoute.coin(it.id, asset))
                            }
                        }
                    },
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
                    wallet?.let { board.changeWatchlist(it.id) { list -> list.copy(sort = next) } }
                },
                onClose = { sortOpen = false },
            )
        }
    }
}

/**
 * The line under the watchlist: when the newest shown price was read, and that the change is over 24 hours. A price from before today, [nowMs] in
 * [zone], also gives its day, so a kept price never reads as today's. Null before any price is read.
 */
internal fun footnoteOf(asOfMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? = asOfMs?.let {
    val day = Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
    val on = if (day == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()) "" else " on ${FOOTNOTE_DAY.format(day)}"
    "Prices at ${clockOf(it, zone)}$on, change over 24 hours."
}

/** The day of a price from before today, as the footnote writes it: "25 Nov". */
private val FOOTNOTE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * The account that Home's Send sends from: the first one that holds some of its coin, else the first one. Null when the wallet has no account on the
 * side, and Send then does nothing.
 */
private fun sendSourceOf(accounts: List<WalletAccount>): WalletAccount? =
    accounts.firstOrNull { (it.balance?.balance?.signum() ?: 0) > 0 } ?: accounts.firstOrNull()

/** One watched asset as its row: its name and ticker, its logo, its price and change, and the ticks of its day's prices. A test network shows no price. */
private fun rowOf(
    asset: WatchedAsset,
    tokens: Map<WalletAccount, List<TokenHolding>>,
    price: Price?,
    day: PriceSeries?,
    testnet: Boolean,
    fiat: Fiat,
    app: LumioApp,
): WatchlistRow {
    val (symbol, name) = namesOf(asset, app, tokens.values)
    return WatchlistRow(
        asset = asset,
        name = name,
        ticker = symbol,
        icon = coinIconOf(asset, symbol, app),
        priceUsd = if (testnet || price == null) null else fiat.amount(price.usd),
        change24hPct = if (testnet) null else price?.change24hPct,
        ticks = if (testnet) emptyList() else day?.points?.map { it.usd }.orEmpty(),
    )
}

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
