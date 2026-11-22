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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.lumio.data.asset.AssetId
import app.lumio.data.price.Price
import app.lumio.data.net.Networks
import app.lumio.home.HomeAsset
import app.lumio.network.NetworkMode
import app.lumio.ui.home.HomeScreen
import app.lumio.ui.home.WatchlistRow
import app.lumio.ui.home.WatchlistSortSheet
import app.lumio.ui.kit.LumioTab
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletsView
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort
import app.lumio.watchlist.WatchlistStore
import app.lumio.watchlist.ordered
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Home tab: the watchlist of the active wallet, with the prices the price source reads and the native amounts its accounts hold. The active
 * wallet is the first paired wallet until the profile panel can choose one. The board is the Accounts tab's, so the rows show from its first read.
 * Send and Receive open the wallet's page, where each account has its own.
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
    val view = board.view
    val wallet = view?.wallets?.firstOrNull()
    var stored by remember(wallet?.id) { mutableStateOf<WalletWatchlist?>(null) }
    LaunchedEffect(wallet?.id) {
        val walletId = wallet?.id ?: return@LaunchedEffect
        stored = withContext(Dispatchers.IO) { WatchlistStore.read(session, walletId) } ?: WalletWatchlist(assets = FIRST_RUN_ASSETS)
    }
    val watchlist = stored
    val prices by produceState(initialValue = emptyMap<AssetId, Price>(), watchlist?.assets) {
        val assets = watchlist?.assets.orEmpty()
        value = withContext(Dispatchers.IO) { app.prices.assetPrices(assets.map { it.assetId }) }
    }
    val testnet = selection.mode == NetworkMode.TESTNET
    val rows = watchlist?.ordered { prices[it.assetId]?.change24hPct }.orEmpty().map { asset ->
        rowOf(asset, view, wallet?.id, prices[asset.assetId], testnet)
    }
    var sortOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun choose(next: WalletWatchlist) {
        val walletId = wallet?.id ?: return
        stored = next
        scope.launch(Dispatchers.IO) { WatchlistStore.write(session, walletId, next) }
    }
    val sort = watchlist?.sort ?: WatchlistSort.MARKET_CAP
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
                    watchlist?.let { choose(it.copy(sort = next)) }
                },
                onClose = { sortOpen = false },
            )
        }
    }
}

/** One watched asset as its row: its name and symbol, what the wallet holds of it, and its price and change when the testnet side is not on. */
private fun rowOf(asset: WatchedAsset, view: WalletsView?, walletId: String?, price: Price?, testnet: Boolean): WatchlistRow {
    val (symbol, name) = coinNames(asset)
    val held = if (view != null && walletId != null) heldOf(asset, view.accountsOf(walletId)) else null
    return WatchlistRow(
        asset = asset,
        name = name,
        detail = if (held == null) symbol else "$symbol · ${held.stripTrailingZeros().toPlainString()} held",
        priceUsd = if (testnet || price == null) null else FIAT.format(price.usd),
        change24hPct = if (testnet) null else price?.change24hPct,
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

/** Dollars to the cent, as the watchlist writes a price. */
private val FIAT: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
