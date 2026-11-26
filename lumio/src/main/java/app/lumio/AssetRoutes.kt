package app.lumio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.airvault.core.model.ChainId
import app.lensly.app.VaultHolder
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.navigation.GateRoutes
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.asset.AssetId
import app.lumio.data.config.Fiat
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.decodeLogo
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.home.Masking
import app.lumio.network.NetworkMode
import app.lumio.portfolio.ActivityPage
import app.lumio.portfolio.PortfolioReads
import app.lumio.receive.ReceiveAnswers
import app.lumio.tokens.TokenHolding
import app.lumio.transactions.ActivityFilter
import app.lumio.ui.asset.AssetPage
import app.lumio.ui.asset.CoinAccount
import app.lumio.ui.asset.CoinHeld
import app.lumio.ui.asset.HoldingsPage
import app.lumio.ui.asset.PriceChange
import app.lumio.ui.asset.accountDetail
import app.lumio.ui.asset.accountName
import app.lumio.ui.asset.amountText
import app.lumio.ui.asset.canHold
import app.lumio.ui.asset.chainHoldings
import app.lumio.ui.asset.holdingsOf
import app.lumio.ui.asset.holdingsSummary
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.changeText
import app.lumio.ui.home.letterOf
import app.lumio.ui.home.tokenIcon
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.receive.ReceiveFlowSheet
import app.lumio.ui.receive.receiveCoinStartOf
import app.lumio.ui.transactions.ActivityRowItem
import app.lumio.ui.wallets.AssetRows
import app.lumio.ui.wallets.NATIVE_ASSET_KEY
import app.lumio.wallets.WalletAccount
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchedLabel
import app.lumio.watchlist.Watchlist
import app.lumio.watchlist.toggled
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


/** The newest activity rows of the coin that the Asset page lists. The page's All link opens the whole activity of the coin. */
private const val COIN_ACTIVITY_ROWS = 10

/** What one read of a coin's activity depends on. */
internal data class CoinActivityKey(val asset: WatchedAsset, val mode: NetworkMode, val includeSpam: Boolean)

/**
 * The coin activity read ahead of the push to the Asset page, so the page lists its activity on its first frame. The vault keeps the last page read, and
 * drops it when it locks or is erased. The Asset page reads again behind it, so a kept page is only ever a first frame.
 */
internal object CoinActivityPreload {
    /** How long a tap waits for the activity before it pushes the page without it. */
    const val WAIT_MS = 150L

    fun pageOf(vault: LumioVault?, key: CoinActivityKey): ActivityPage? = vault?.keptCoinActivity?.takeIf { it.first == key }?.second

    /** Reads the activity of [key], with the last prices kept unless [live], and keeps it in [vault]. Returns null when the vault locked during the read. */
    suspend fun read(app: LumioApp, vault: LumioVault, key: CoinActivityKey, live: Boolean = true): ActivityPage? = try {
        PortfolioReads.activity(app, vault, key.mode, walletId = null, filter = ActivityFilter(coin = key.asset), includeSpam = key.includeSpam, live = live)
            .also { vault.keptCoinActivity = key to it }
    } catch (_: IllegalStateException) {
        null
    }

    /**
     * Reads the activity on the vault's work scope, which a lock cancels, and waits [WAIT_MS] for it. A read that takes longer goes on and keeps its page for the Asset page's
     * first frame, as only the wait ends. [load] is the read, which a test replaces to make it slow.
     */
    suspend fun preload(app: LumioApp, vault: LumioVault, key: CoinActivityKey, load: suspend () -> Unit = { read(app, vault, key, live = false) }) {
        val read = vault.scans.launch { load() }
        withTimeoutOrNull(WAIT_MS) { read.join() }
    }
}

/**
 * The Asset page (C4) of [asset] across the wallet [walletId]. The price, the day's chart, the watchlist and the holdings come from the hoisted board
 * and the vault, so the page is populated on its first frame. A range the board lacks, and the coin's activity, load in their places when their reads
 * answer. Send and Receive open on this coin, so a token is preselected. With no [walletId], which is a watchlist row opened with no wallet paired,
 * the page is the price only: the coin's price, change and chart, and no holdings, activity, Send or Receive.
 */
@Composable
internal fun AssetRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    walletId: String?,
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
    val wallet = walletId?.let { id -> view.wallets.firstOrNull { it.id == id } }
    if (walletId != null && wallet == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val display = rememberDisplay(app)
    val (symbol, name) = namesOf(asset, app, board.tokenHeld.values)
    val coinIcon = coinIconOf(asset, symbol, app)
    val logo by produceState<ImageBitmap?>(initialValue = null, asset) {
        val contract = asset.contract
        value = if (contract == null || coinIcon !is AssetIconSpec.Letter) {
            null
        } else {
            withContext(Dispatchers.IO) { app.logos.imageOf(asset.network, contract)?.let { decodeLogo(it)?.asImageBitmap() } }
        }
    }
    val assetId = asset.assetId
    var range by rememberSaveable(stateSaver = FrameSaver) { mutableStateOf(ChartFrame.H1) }
    val series = rememberRangeChart(assetId, range, board.dayChartOf(assetId)) { app.prices.chart(assetId, it) }
    val price by produceState<Price?>(initialValue = board.prices[assetId], assetId) {
        if (value == null) value = withContext(Dispatchers.IO) { app.prices.assetPrices(listOf(assetId))[assetId] }
    }
    val current = board.watchlist ?: Watchlist(assets = FIRST_RUN_ASSETS)
    val watched = asset in current.assets
    val accounts = if (wallet != null) view.accountsOf(wallet.id) else emptyList()
    val holdings = holdingsOf(asset, symbol, accounts, board.tokenHeld)
    val chains = chainHoldings(holdings, wallet?.name.orEmpty())
    val total = holdings.map { it.amount }.reduceOrNull(java.math.BigDecimal::add)
    val fiat = LocalFiat.current
    val held = total?.let { sum ->
        CoinHeld(
            amount = Masking.amount(amountText(sum, symbol), hidden = !display.showAssets),
            summary = holdingsSummary(accounts = holdings.size, chains = chains.size),
            value = price?.let { Masking.amount(fiat.amount(sum.toDouble() * it.usd), hidden = !display.showAssets) },
            chainIcons = chains.map { AssetRows.nativeIcon(it.network, symbol, app.registry::network) },
        )
    }
    var receive by remember { mutableStateOf<ReceiveAnswers?>(null) }
    var receiveOpen by remember { mutableStateOf(false) }
    var receiveOpening by remember { mutableIntStateOf(0) }
    val networkIcon = AssetRows.nativeIcon(asset.network, symbol, app.registry::network)
    val coinAccounts = accounts.filter { canHold(it, asset) }.map { account ->
        val amount = holdings.firstOrNull { it.account == account }?.amount
        CoinAccount(
            title = accountName(account),
            detail = accountDetail(wallet?.name.orEmpty(), account),
            trailing = amount?.let { Masking.amount(amountText(it, symbol), hidden = !display.showAssets) },
            onSend = coinSendRoute(account, asset, selection.mode)?.let { route -> { navController.navigate(route) } },
            onReceive = {
                receive = receiveCoinStartOf(account, asset.network, symbol, name, coinIcon, networkIcon, held = total != null, wallets = view.wallets)
                receiveOpening++
                receiveOpen = true
            },
        )
    }
    val vault = app.lumioVault()
    val includeSpam = rememberTransactions(app).activityIncludesSpam
    val activityKey = CoinActivityKey(asset, selection.mode, includeSpam)
    val coinActivity by produceState(initialValue = CoinActivityPreload.pageOf(vault, activityKey), vault, activityKey, listVersion) {
        if (vault != null && wallet != null) {
            // The rows show with the last prices kept, and the sources' answer replaces them when it lands.
            if (value == null) CoinActivityPreload.read(app, vault, activityKey, live = false)?.let { value = it }
            CoinActivityPreload.read(app, vault, activityKey)?.let { value = it }
        }
    }
    AssetPage(
        name = name,
        symbol = symbol,
        icon = { CoinLogo(logo = logo, spec = coinIcon) },
        priceText = price?.let { fiat.amount(it.usd) },
        change = price?.let { changeOf(it, fiat) },
        series = series,
        range = range,
        onRange = { range = it },
        watched = watched,
        onWatch = { board.changeWatchlist { it.toggled(asset) } },
        held = held,
        priceOnly = wallet == null,
        onHoldings = { wallet?.let { navController.navigate(LumioRoute.holdings(it.id, asset)) } },
        accounts = coinAccounts,
        onBack = { navController.popBackStack() },
        onLock = onLock,
        onAllActivity = { navController.navigate(LumioRoute.activity(asset)) },
        activity = {
            coinActivity?.let { page ->
                val rows = page.items.take(COIN_ACTIVITY_ROWS)
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
                        names = page.names,
                        zone = ZoneId.systemDefault(),
                        shown = display.showAssets,
                        onClick = { wallet?.let { navController.navigate(LumioRoute.transaction(it.id, item.tx.account, item.tx.id)) } },
                    )
                }
            }
        },
    )
    receive?.let { start ->
        ReceiveFlowSheet(visible = receiveOpen, opening = receiveOpening, start = start, assets = emptyList(), onClose = { receiveOpen = false })
    }
}

/** The frame the selector keeps across recreation, by its name. A name this build does not know falls back to the default frame, the 1-hour one. */
private val FrameSaver: Saver<ChartFrame, String> = Saver(
    save = { it.name },
    restore = { name -> ChartFrame.entries.firstOrNull { it.name == name } ?: ChartFrame.H1 },
)

/**
 * The price chart of [range] for [assetId]. The 1-hour frame starts on [dayChart], the day chart the board holds, so its first frame is populated and
 * needs no read. Any other frame, and a 1-hour frame the board lacks, is read through [read]. The chart is cleared before each read, so a frame never
 * shows another frame's chart under its label.
 */
@Composable
internal fun rememberRangeChart(
    assetId: AssetId,
    range: ChartFrame,
    dayChart: PriceSeries?,
    read: suspend (ChartFrame) -> PriceSeries?,
): PriceSeries? {
    val series by produceState<PriceSeries?>(initialValue = dayChart.takeIf { range == ChartFrame.H1 }, assetId, range) {
        value = dayChart.takeIf { range == ChartFrame.H1 }
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { read(range) }.getOrNull() }
    }
    return series
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
        chains = chainHoldings(holdingsOf(asset, symbol, view.accountsOf(walletId), board.tokenHeld), wallet.name),
        showBalance = display.showAssets,
        onAccount = { navController.navigate(LumioRoute.account(it.walletId, it.accountId)) },
        onBack = { navController.popBackStack() },
        onLock = onLock,
    )
}

/** The navigation arguments of the coin routes, in the order [LumioRoute.coin] writes them. */
internal fun coinArguments(): List<NamedNavArgument> =
    listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }) + coinAssetArguments()

/** The navigation arguments of a coin with no wallet, as [LumioRoute.ACTIVITY_COIN] takes them. */
internal fun coinAssetArguments(): List<NamedNavArgument> = listOf(
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

/** The change of [price] over the chart's day, as the page writes it: the percent, then the dollars it moved. */
private fun changeOf(price: Price, fiat: Fiat): PriceChange? {
    val percent = price.change24hPct ?: return null
    val delta = price.usd - price.usd / (1 + percent / 100)
    val sign = if (delta >= 0) "+" else "−"
    return PriceChange(text = "${changeText(percent)} · $sign${fiat.amount(abs(delta))} Today", rising = percent >= 0)
}

/**
 * The symbol and the name of [asset], the same on Home, Add to Watchlist and the asset page: the names kept with it when it was starred from Add to
 * Watchlist, which are the market source's (see [WatchedLabel]). Else a token's come from the registry, else from the wallet's holdings of the token,
 * else the address that [coinNames] gives. A holding names it only when it is on the asset's own network.
 */
internal fun namesOf(asset: WatchedAsset, app: LumioApp, held: Collection<List<TokenHolding>>): Pair<String, String> = namesOf(
    asset,
    asset.contract?.takeIf { asset.network.isEvm }?.let { app.registry.token(Networks.evmIdOf(asset.network.chain), it) },
    held,
    app.lumioVault()?.wallets?.labelOf(asset),
)

/** The symbol and the name of [asset], given [listed], the registry's entry for its token if it has one, and [label], the names kept with it. */
internal fun namesOf(asset: WatchedAsset, listed: TokenEntry?, held: Collection<List<TokenHolding>>, label: WatchedLabel? = null): Pair<String, String> {
    label?.let { return it.symbol to it.name }
    val contract = asset.contract ?: return coinNames(asset)
    listed?.let { return it.symbol to it.name }
    val token = held.asSequence().flatten()
        .firstOrNull { it.token.contract.equals(contract, ignoreCase = true) && Networks.evm(it.chain.chainId) == asset.network.chain && it.chain.testnet == asset.network.testnet }
        ?.token
    return token?.let { it.symbol to it.name } ?: coinNames(asset)
}

/**
 * The logo of [asset]: a native coin's icon, or a token's own logo when Lensly ships one, else the image kept with it when it was starred from Add to
 * Watchlist, else the letter of its [symbol]. It never asks a source for a watched token's logo, so a row names no contract to one.
 */
internal fun coinIconOf(asset: WatchedAsset, symbol: String, app: LumioApp): AssetIconSpec =
    iconOf(asset, symbol, app, app.lumioVault()?.wallets?.labelOf(asset))

/** The logo of [asset] as [coinIconOf] picks it, given [label], the names and image kept with it. */
internal fun iconOf(asset: WatchedAsset, symbol: String, app: LumioApp, label: WatchedLabel?): AssetIconSpec {
    val contract = asset.contract ?: return AssetRows.nativeIcon(asset.network, symbol, app.registry::network)
    val shipped = if (asset.network.isEvm) {
        val chainId = Networks.evmIdOf(asset.network.chain)
        tokenIcon(app.registry.token(chainId, contract), app.registry.network(chainId), symbol)
    } else {
        AssetIconSpec.Letter(letterOf(symbol))
    }
    val image = label?.image
    return if (shipped is AssetIconSpec.Letter && image != null) AssetIconSpec.Image(image, shipped.letter) else shipped
}

/**
 * The Send of [asset] from [account]. An Ethereum account opens the send on the asset's network with its token chosen, and a native coin sends from its
 * own account. A token of another family has no send, so it is null and the page does not offer it: it never opens the native coin's send.
 */
internal fun coinSendRoute(account: WalletAccount, asset: WatchedAsset, mode: NetworkMode): String? = when {
    account.namespace == Networks.EIP155 && asset.network.isEvm -> LumioRoute.send(account.walletId, account.id, Networks.evmIdOf(asset.network.chain), asset.contract)
    asset.contract != null -> null
    else -> sendRoute(account, mode)
}

/** The coin's logo at 48 dp: the image a source served when there is one, else the icon [spec] draws. */
@Composable
private fun CoinLogo(logo: ImageBitmap?, spec: AssetIconSpec) {
    if (logo == null) {
        AssetIcon(spec = spec, size = ICON_SIZE)
    } else {
        Image(bitmap = logo, contentDescription = null, modifier = Modifier.size(ICON_SIZE).clip(CircleShape))
    }
}

private val ICON_SIZE = 48.dp
