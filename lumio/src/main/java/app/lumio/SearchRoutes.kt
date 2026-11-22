package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.components.groupAddress
import app.lumio.data.provider.TrendingCoin
import app.lumio.data.provider.TrendingList
import app.lumio.search.SearchEntry
import app.lumio.search.looksLikeAddress
import app.lumio.search.matching
import app.lumio.ui.home.FIAT
import app.lumio.ui.home.changeText
import app.lumio.ui.search.SearchRow
import app.lumio.ui.search.SearchSection
import app.lumio.ui.search.SearchSheet
import app.lumio.ui.search.SearchTab
import app.lumio.wallets.WalletsView
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A result's target: the action that opens its page. */
private typealias Open = () -> Unit

/**
 * The search sheet's content, over the open vault's wallets: the coins they hold, their accounts and the chains those accounts are on, and the active
 * wallet's watchlist. Picking a result closes the sheet and opens its page. A result with no page of its own yet opens the wallet that holds it.
 */
@Composable
internal fun SearchRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    val session = holder.openSession() ?: return
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    val view = board.view ?: return
    val wallet = view.wallets.firstOrNull()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(SearchTab.MAIN) }
    val watched by produceState(initialValue = emptyList<WatchedAsset>(), wallet?.id) {
        val walletId = wallet?.id
        value = if (walletId == null) emptyList() else withContext(Dispatchers.IO) {
            WatchlistStore.read(session, walletId)?.assets ?: FIRST_RUN_ASSETS
        }
    }
    fun openRoute(route: String) {
        onClose()
        navController.navigate(route)
    }
    val needle = query.trim()
    LaunchedEffect(tab) {
        if (tab == SearchTab.TRENDING) board.refreshTrending()
    }
    // The Trending tab lists the coins without a query, and filters them as the owner types.
    val sections: List<SearchSection> = when (tab) {
        SearchTab.TRENDING -> listOfNotNull(section(null, trendingRows(board.trending?.value, needle)))
        SearchTab.MAIN -> if (needle.isEmpty()) emptyList() else mainSections(needle, view, ::openRoute)
        SearchTab.WATCHLIST -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, watchEntries(watched, wallet?.id, ::openRoute)).rows()))
        SearchTab.CHAINS -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, chainEntries(view, ::openRoute)).rows()))
    }
    SearchSheet(
        query = query,
        onQuery = { query = it },
        tab = tab,
        onTab = { tab = it },
        sections = sections,
        onClose = onClose,
    )
}

/** The Main tab: the address row when a whole address is pasted, then the coins, the accounts and the chains, each under its own label. */
private fun mainSections(query: String, view: WalletsView, openRoute: (String) -> Unit): List<SearchSection> {
    val wallet = view.wallets.firstOrNull()
    val addressRows = if (wallet != null && looksLikeAddress(query)) {
        listOf(SearchRow(key = "send-to-address", title = "Send to This Address", detail = groupAddress(query), onOpen = { openRoute(LumioRoute.wallet(wallet.id)) }))
    } else {
        emptyList()
    }
    return listOfNotNull(
        section(null, addressRows),
        section("Tokens", matching(query, coinEntries(view, openRoute)).rows()),
        section("Your Accounts", matching(query, accountEntries(view, openRoute)).rows()),
        section("Chains", matching(query, chainEntries(view, openRoute)).rows()),
    )
}

/** A section of [rows] under [label], or null when there are no rows to show. */
private fun section(label: String?, rows: List<SearchRow>): SearchSection? = if (rows.isEmpty()) null else SearchSection(label, rows)

/** The trending coins that match [needle], or all of them when it is blank. A trending coin has no page yet, so its row opens nothing. */
private fun trendingRows(list: TrendingList?, needle: String): List<SearchRow> = list?.coins.orEmpty()
    .filter { coin -> needle.isEmpty() || coin.name.contains(needle, ignoreCase = true) || coin.symbol.contains(needle, ignoreCase = true) }
    .mapIndexed { index, coin -> SearchRow(key = "$index-${coin.symbol}", title = coin.name, detail = trendingDetail(coin), onOpen = null) }

/** A trending coin's line: its ticker, its rank by market cap, its price and its 24 h change, each when the source gives it. */
private fun trendingDetail(coin: TrendingCoin): String = listOfNotNull(
    coin.symbol,
    coin.rank?.let { "#$it" },
    coin.priceUsd?.let { FIAT.format(it) },
    coin.change24hPct?.let { changeText(it) },
).joinToString(" · ")

/** The search rows of these entries, each keyed by its position and opening its target. */
private fun List<SearchEntry<Open>>.rows(): List<SearchRow> = mapIndexed { index, entry ->
    SearchRow(key = "$index-${entry.title}", title = entry.title, detail = entry.detail, onOpen = entry.target)
}

/** One entry per coin that an account holds, found by its name and its ticker. It opens the wallet that holds the coin. */
private fun coinEntries(view: WalletsView, openRoute: (String) -> Unit): List<SearchEntry<Open>> = view.wallets
    .flatMap { wallet -> view.accountsOf(wallet.id).mapNotNull { account -> account.balance?.let { wallet.id to it } } }
    .filter { (_, balance) -> balance.balance.signum() > 0 }
    .distinctBy { (_, balance) -> balance.assetId }
    .map { (walletId, balance) ->
        val coin = WatchedAsset(balance.network)
        val (symbol, name) = coinNames(coin)
        SearchEntry(title = name, detail = symbol, terms = listOf(name, symbol), target = { openRoute(LumioRoute.coin(walletId, coin)) })
    }

/** One entry per account of every wallet, found by its network, its wallet's name and its address. */
private fun accountEntries(view: WalletsView, openRoute: (String) -> Unit): List<SearchEntry<Open>> = view.wallets.flatMap { wallet ->
    view.accountsOf(wallet.id).map { account ->
        SearchEntry(
            title = account.networkName,
            detail = wallet.name,
            terms = listOf(account.networkName, wallet.name, account.address),
            target = { openRoute(LumioRoute.account(wallet.id, account.id)) },
        )
    }
}

/** One entry per chain that an account is on. It opens the networks settings, which list the chains as the Network page will. */
private fun chainEntries(view: WalletsView, openRoute: (String) -> Unit): List<SearchEntry<Open>> = view.wallets
    .flatMap { wallet -> view.accountsOf(wallet.id) }
    .map { it.networkName }
    .distinct()
    .map { network ->
        SearchEntry(title = network, detail = "Chain", terms = listOf(network), target = { openRoute(LumioRoute.SETTINGS_NETWORKS) })
    }

/** One entry per watched asset of the wallet [walletId], found by its name and its ticker. No wallet means no watchlist. */
private fun watchEntries(assets: List<WatchedAsset>, walletId: String?, openRoute: (String) -> Unit): List<SearchEntry<Open>> {
    if (walletId == null) return emptyList()
    return assets.map { asset ->
        val (symbol, name) = coinNames(asset)
        SearchEntry(title = name, detail = symbol, terms = listOf(name, symbol), target = { openRoute(LumioRoute.coin(walletId, asset)) })
    }
}
