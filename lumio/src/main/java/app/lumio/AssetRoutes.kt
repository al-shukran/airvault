package app.lumio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.airvault.core.model.ChainId
import app.lensly.app.VaultHolder
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.net.NetworkId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.home.Masking
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.asset.AssetPage
import app.lumio.ui.asset.CoinHeld
import app.lumio.ui.asset.HoldingsPage
import app.lumio.ui.asset.PriceChange
import app.lumio.ui.asset.amountText
import app.lumio.ui.asset.chainHoldings
import app.lumio.ui.asset.fiatText
import app.lumio.ui.asset.holdingsSummary
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.changeText
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.transactions.ActivityRowItem
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.NATIVE_ASSET_KEY
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistStore
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The newest activity rows of the coin that the Asset page lists. The page's All link opens the whole activity. */
private const val COIN_ACTIVITY_ROWS = 10

/**
 * The Asset page (C4) of [asset] across the wallet [walletId]. The price, the watchlist and the holdings are read from the hoisted board and the
 * vault, so the page is populated on its first frame; the chart loads in its place when the price source answers.
 */
@Composable
internal fun AssetRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    walletId: String,
    asset: WatchedAsset,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    val view = board.view ?: return
    val wallet = view.wallets.firstOrNull { it.id == walletId }
    if (wallet == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val display = rememberDisplay(app)
    val (symbol, name) = coinNames(asset)
    val assetId = asset.assetId
    var range by rememberSaveable { mutableStateOf(ChartRange.D1) }
    val series by produceState<PriceSeries?>(initialValue = null, assetId, range) {
        value = withContext(Dispatchers.IO) { runCatching { app.prices.chart(assetId, range) }.getOrNull() }
    }
    val price by produceState<Price?>(initialValue = null, assetId) {
        value = withContext(Dispatchers.IO) { app.prices.assetPrices(listOf(assetId))[assetId] }
    }
    var stored by remember(walletId) { mutableStateOf<WalletWatchlist?>(null) }
    LaunchedEffect(walletId) {
        stored = withContext(Dispatchers.IO) { WatchlistStore.read(session, walletId) } ?: WalletWatchlist(assets = FIRST_RUN_ASSETS)
    }
    val current = stored ?: WalletWatchlist(assets = FIRST_RUN_ASSETS)
    val watched = asset in current.assets
    val scope = rememberCoroutineScope()
    fun toggleWatch() {
        val next = current.copy(assets = if (watched) current.assets - asset else current.assets + asset)
        stored = next
        scope.launch(Dispatchers.IO) { WatchlistStore.write(session, walletId, next) }
    }
    val chains = if (asset.contract == null) chainHoldings(view.accountsOf(walletId), symbol, wallet.name) else emptyList()
    val total = chains.map { it.total }.reduceOrNull(java.math.BigDecimal::add)
    val held = total?.let { sum ->
        CoinHeld(
            amount = Masking.amount(amountText(sum, symbol), hidden = !display.showAssets),
            summary = holdingsSummary(accounts = chains.sumOf { it.accounts.size }, chains = chains.size),
            value = price?.let { Masking.amount(fiatText(sum.toDouble() * it.usd), hidden = !display.showAssets) },
        )
    }
    val names = app.lumioVault()?.portfolio?.activity?.names.orEmpty()
    val rows = app.lumioVault()?.portfolio?.activity?.items.orEmpty()
        .filter { ownsCoin(it, asset, symbol) }
        .take(COIN_ACTIVITY_ROWS)
    AssetPage(
        name = name,
        symbol = symbol,
        icon = { CoinIcon(asset = asset, symbol = symbol, app = app) },
        priceText = price?.let { fiatText(it.usd) },
        change = price?.let { changeOf(it) },
        series = series,
        range = range,
        onRange = { range = it },
        watched = watched,
        onWatch = ::toggleWatch,
        held = held,
        onHoldings = { navController.navigate(LumioRoute.holdings(walletId, asset)) },
        onSend = { navController.navigate(LumioRoute.wallet(walletId)) },
        onReceive = { navController.navigate(LumioRoute.wallet(walletId)) },
        onBack = { navController.popBackStack() },
        onLock = onLock,
        onAllActivity = { navController.navigate(LumioRoute.ACTIVITY) },
        activity = {
            if (rows.isEmpty()) {
                Text(
                    text = "No activity yet. Your first receive will appear here.",
                    style = ClearAirType.body,
                    color = LocalClearAirPalette.current.ink2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                )
            }
            rows.forEach { item ->
                ActivityRowItem(
                    item = item,
                    names = names,
                    zone = ZoneId.systemDefault(),
                    shown = display.showAssets,
                    onClick = { navController.navigate(LumioRoute.transaction(walletId, item.tx.account, item.tx.id)) },
                )
            }
        },
    )
}

/**
 * Holdings Breakdown (C5) of [asset] in the wallet [walletId]: the accounts that hold it, one group per chain, each account opening its page.
 */
@Composable
internal fun HoldingsRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    walletId: String,
    asset: WatchedAsset,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(GateRoutes.GATE)
    }
    if (session == null) return
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    val view = board.view ?: return
    val wallet = view.wallets.firstOrNull { it.id == walletId }
    if (wallet == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val display = rememberDisplay(app)
    val symbol = coinNames(asset).first
    HoldingsPage(
        title = "Your $symbol",
        symbol = symbol,
        chains = if (asset.contract == null) chainHoldings(view.accountsOf(walletId), symbol, wallet.name) else emptyList(),
        showBalance = display.showAssets,
        onAccount = { navController.navigate(LumioRoute.account(it.walletId, it.accountId)) },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/** The navigation arguments of the coin routes, in the order [LumioRoute.coin] writes them. */
internal fun coinArguments(): List<NamedNavArgument> = listOf(
    navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType },
    navArgument(LumioRoute.COIN_CHAIN_ARG) { type = NavType.StringType },
    navArgument(LumioRoute.COIN_TESTNET_ARG) { type = NavType.StringType },
    navArgument(LumioRoute.COIN_CONTRACT_ARG) { type = NavType.StringType },
)

/** The wallet of a coin route. */
internal fun NavBackStackEntry.coinWallet(): String = arguments?.getString(LumioRoute.WALLET_ARG).orEmpty()

/** The coin of a coin route: its chain, its testnet flag, and its contract, where the native key stands for no contract. */
internal fun NavBackStackEntry.coinAsset(): WatchedAsset {
    val chain = ChainId(arguments?.getString(LumioRoute.COIN_CHAIN_ARG).orEmpty())
    val testnet = arguments?.getString(LumioRoute.COIN_TESTNET_ARG) == "true"
    val contract = arguments?.getString(LumioRoute.COIN_CONTRACT_ARG)?.takeIf { it != NATIVE_ASSET_KEY }
    return WatchedAsset(network = NetworkId(chain = chain, testnet = testnet), contract = contract)
}

/** Whether [item] moves the coin of [asset]: the native coin of [symbol] on any chain, or the token of the watched contract. */
private fun ownsCoin(item: TxFeedItem, asset: WatchedAsset, symbol: String): Boolean {
    val contract = item.tx.asset.contract
    return if (asset.contract == null) contract == null && item.tx.asset.symbol == symbol else contract.equals(asset.contract, ignoreCase = true)
}

/** The change of [price] over the chart's day, as the page writes it: the percent, then the dollars it moved. */
private fun changeOf(price: Price): PriceChange? {
    val percent = price.change24hPct ?: return null
    val delta = price.usd - price.usd / (1 + percent / 100)
    val sign = if (delta >= 0) "+" else "−"
    return PriceChange(text = "${changeText(percent)} · $sign${fiatText(abs(delta))} Today", rising = percent >= 0)
}

/** The coin's logo at 48 dp: the native coin's icon, or the ticker in a monogram for a token, whose logo is not read yet. */
@Composable
private fun CoinIcon(asset: WatchedAsset, symbol: String, app: LumioApp) {
    if (asset.contract == null) {
        AssetIcon(spec = AssetRows.nativeIcon(asset.network, symbol, app.registry::network), size = ICON_SIZE)
    } else {
        ClearAirMonogram(text = symbol.take(MONOGRAM_LENGTH), tile = ICON_SIZE)
    }
}

private val ICON_SIZE = 48.dp
private const val MONOGRAM_LENGTH = 3
