package app.lumio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.lensly.app.VaultHolder
import app.lensly.ui.components.groupAddress
import app.lumio.data.config.Fiat
import app.lumio.data.net.Networks
import app.lumio.data.provider.CoinDirectory
import app.lumio.data.provider.DirectoryCoin
import app.lumio.data.provider.MARKET_PAGE
import app.lumio.data.provider.TrendingCoin
import app.lumio.data.provider.TrendingList
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.search.SearchEntry
import app.lumio.search.looksLikeAddress
import app.lumio.search.matching
import app.lumio.send.algorand.AddAssetRows
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.changeText
import app.lumio.ui.home.letterOf
import app.lumio.ui.home.tokenIcon
import app.lensly.ui.components.chainIconDrawables
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.search.SearchRow
import app.lumio.ui.search.SearchSection
import app.lumio.ui.search.SearchSheet
import app.lumio.ui.search.SearchTab
import app.lumio.ui.search.Candidate
import app.lumio.ui.search.PickerLogo
import app.lumio.ui.search.PickerRow
import app.lumio.ui.search.searchCandidates
import app.lumio.ui.search.searchMatches
import app.lumio.ui.search.topCandidates
import app.lumio.ui.search.watchableEntries
import app.lumio.ui.search.WatchlistPicker
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.ui.settings.providerStatusWord
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletsView
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistStore
import app.lumio.watchlist.toggled
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
    // The watchlist is the wallet's the profile panel chose, as Home shows it, and the first paired wallet until the owner chooses one.
    val wallet = view.wallets.active(selection) { it.id }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(SearchTab.MAIN) }
    val watched by produceState(initialValue = emptyList<WatchedAsset>(), wallet?.id) {
        val walletId = wallet?.id
        value = if (walletId == null) emptyList() else withContext(Dispatchers.IO) {
            WatchlistStore.read(session, walletId)?.assets ?: FIRST_RUN_ASSETS
        }
    }
    val vault = app.lumioVault()
    val providers by produceState<ProviderLists?>(initialValue = null, vault, tab) {
        if (vault != null && tab == SearchTab.PROVIDERS) value = withContext(Dispatchers.IO) { readLists(app, vault) }
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
        SearchTab.TRENDING -> listOfNotNull(section(null, trendingRows(board.trending?.value, needle, LocalFiat.current)))
        SearchTab.MAIN -> if (needle.isEmpty()) emptyList() else mainSections(needle, view, wallet, selection.mode, ::openRoute)
        SearchTab.WATCHLIST -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, watchEntries(watched, wallet?.id, ::openRoute)).rows()))
        SearchTab.ADDRESS_BOOK -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, addressBookEntries(view, ::openRoute)).rows()))
        SearchTab.ETH -> if (needle.isEmpty()) emptyList() else chainTabSections(needle, view, Networks.EIP155, ::openRoute)
        SearchTab.SOL -> if (needle.isEmpty()) emptyList() else chainTabSections(needle, view, Networks.SOLANA, ::openRoute)
        SearchTab.CHAINS -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, chainEntries(view, ::openRoute)).rows()))
        SearchTab.PROVIDERS -> if (needle.isEmpty()) emptyList() else listOfNotNull(section(null, matching(needle, providerEntries(providers, ::openRoute)).rows()))
    }
    SearchSheet(
        query = query,
        onQuery = { query = it },
        tab = tab,
        onTab = { tab = it },
        sections = sections,
    )
}

/**
 * Add to Watchlist, over the active wallet. With no query it lists Top by Market Cap: the market source's coins by market cap that Lumio can watch,
 * in the source's order. Typing finds the coins of the source's directory that Lumio can watch, by name and ticker as the search does, one row per
 * name, ranked by market cap once their market rows are read. Each row's star adds the asset to the watchlist or takes it off. With no market source
 * switched on, it lists the first-run coins and the watchlist.
 */
@Composable
internal fun WatchlistAddRoute(
    navController: NavHostController,
    holder: VaultHolder,
    selection: NetworkSelection,
    listVersion: Int,
    onLock: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as LumioApp
    holder.openSession() ?: return
    val board = rememberWalletsBoard(app, selection.mode, listVersion) ?: return
    val view = board.view ?: return
    val wallet = view.wallets.active(selection) { it.id } ?: return
    var query by rememberSaveable { mutableStateOf(if (DebugDemoData.flag("watchlist:search")) DEMO_QUERY else "") }
    val current = board.watchlists[wallet.id] ?: WalletWatchlist(assets = FIRST_RUN_ASSETS)
    val evm: (Long) -> Boolean = { chainId -> app.registry.network(chainId)?.testnet == false }
    val market = remember { app.prices.servesCoins() }
    val top by produceState(app.prices.knownTopCoins()) {
        if (value == null) value = withContext(Dispatchers.IO) { app.prices.lastTopCoins()?.value }
        withContext(Dispatchers.IO) { app.prices.topCoins() }?.let { value = it.value }
    }
    // The directory is read once a day; until it lands, the list waits, so its tokens do not appear after the coins.
    val directory by produceState<CoinDirectory?>(app.prices.knownDirectory(), market) {
        if (market && value == null) value = withContext(Dispatchers.IO) { app.prices.directory() } ?: CoinDirectory(emptyList(), 0L)
    }
    val entries by produceState<List<SearchEntry<Pair<DirectoryCoin, WatchedAsset>>>?>(null, directory) {
        value = directory?.let { withContext(Dispatchers.Default) { watchableEntries(it, evm) } }
    }
    val needle = query.trim()
    val found by produceState<List<Candidate>?>(null, needle, entries) {
        val list = entries
        if (needle.isEmpty() || list == null) {
            value = null
            return@produceState
        }
        val matches = withContext(Dispatchers.Default) { searchMatches(needle, list) }
        value = searchCandidates(matches, emptyMap())
        val ids = matches.map { it.first.id }.distinct().take(MARKET_PAGE)
        if (ids.isEmpty()) return@produceState
        // The market rows are read once the owner stops typing, so a fast typist sends one read, not one per letter.
        delay(SEARCH_SETTLE_MS)
        val markets = withContext(Dispatchers.IO) { app.prices.coinMarkets(ids) }?.coins.orEmpty().associateBy { it.id }
        value = searchCandidates(matches, markets)
    }
    val candidates: List<Candidate>? = when {
        !market -> localCandidates(current.assets)
        needle.isNotEmpty() -> found
        else -> top?.let { list -> directory?.let { topCandidates(list, it, evm) } }
    }
    WatchlistPicker(
        query = query,
        onQuery = { query = it },
        label = when {
            needle.isNotEmpty() -> "Results · ${candidates?.size ?: 0}"
            market -> "Top by Market Cap"
            else -> "Coins"
        },
        rows = candidates.orEmpty().map { candidate ->
            val (symbol, name) = pickerNames(candidate, app)
            PickerRow(
                asset = candidate.asset,
                name = name,
                ticker = symbol,
                logo = pickerLogo(candidate, app),
                watched = candidate.asset in current.assets,
                rank = candidate.rank,
                sameName = candidate.sameName,
            )
        },
        onToggle = { asset -> board.changeWatchlist(wallet.id) { it.toggled(asset) } },
        onBack = { navController.popBackStack() },
        onLock = onLock,
        image = { url -> rememberCoinImage(app, url) },
        loading = candidates == null,
    )
}

/** The query the demo's search frame opens with, as the mock's search frame shows it. */
private const val DEMO_QUERY = "government"

/** How long the typing must rest before a search reads the market rows of its results. */
private const val SEARCH_SETTLE_MS = 400L

/** The first-run coins and the watchlist [watched], which the page lists when no market source is switched on. */
private fun localCandidates(watched: List<WatchedAsset>): List<Candidate> = (FIRST_RUN_ASSETS + watched).distinct().map { asset ->
    val (symbol, name) = coinNames(asset)
    Candidate(coinId = asset.toString(), asset = asset, name = name, symbol = symbol, rank = null, image = null)
}

/** The ticker and name of a row: the registry's for a token it lists, as Home and the asset page name it, else the market source's. */
private fun pickerNames(candidate: Candidate, app: LumioApp): Pair<String, String> {
    val asset = candidate.asset
    val contract = asset.contract
    if (contract != null && asset.network.isEvm) app.registry.token(Networks.evmIdOf(asset.network.chain), contract)?.let { return it.symbol to it.name }
    return candidate.symbol.uppercase() to candidate.name
}

/**
 * The logo of a row: the mark Lumio ships, else the image the market row names, else the logo the price source has for the token, as Home reads it.
 */
private fun pickerLogo(candidate: Candidate, app: LumioApp): PickerLogo {
    shippedMark(candidate.asset, app)?.let { return PickerLogo.Mark(it) }
    candidate.image?.let { return PickerLogo.Remote(it) }
    val contract = candidate.asset.contract ?: return PickerLogo.Remote(null)
    return PickerLogo.Mark(AssetIconSpec.Fetched(candidate.asset.network, contract, letterOf(candidate.symbol)))
}

/** The logo Lumio ships for [asset], or null when it ships none, so the market source's image shows instead. */
internal fun shippedMark(asset: WatchedAsset, app: LumioApp): AssetIconSpec? {
    val net = asset.network
    val contract = asset.contract
    if (contract == null) {
        return when {
            net.isBtc -> AssetIconSpec.Bitcoin
            net.isSol -> AssetIconSpec.Solana
            net.isAlgo -> AssetIconSpec.Algorand
            net.isEvm -> app.registry.network(Networks.evmIdOf(net.chain))?.takeIf { it.icon in chainIconDrawables }?.let { AssetIconSpec.Chain(it) }
            else -> null
        }
    }
    if (!net.isEvm) return null
    val chainId = Networks.evmIdOf(net.chain)
    return tokenIcon(app.registry.token(chainId, contract), app.registry.network(chainId), contract).takeUnless { it is AssetIconSpec.Letter }
}

/** The logos decoded this session, by URL, so a row drawn again shows its logo on its first frame. */
private val decodedImages = ConcurrentHashMap<String, ImageBitmap>()

/** The logo at [url], read through [LumioApp.coinImages], or null while it loads or when it does not. */
@Composable
private fun rememberCoinImage(app: LumioApp, url: String): ImageBitmap? {
    val bitmap by produceState(decodedImages[url], url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                app.coinImages.imageOf(url)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }?.also { decodedImages[url] = it }
        }
    }
    return bitmap
}

/**
 * The Main tab: the address row when a whole address is pasted, then the coins and the Algorand assets, the accounts and the chains, each under its own
 * label.
 */
private fun mainSections(query: String, view: WalletsView, wallet: PairedWallet?, mode: NetworkMode, openRoute: (String) -> Unit): List<SearchSection> {
    val algorand = wallet?.let { paired -> view.accountsOf(paired.id).firstOrNull { it.namespace == Networks.ALGORAND } }
    return listOfNotNull(
        section(null, addressRows(query, wallet, openRoute)),
        section("Tokens", matching(query, coinEntries(view, openRoute)).rows() + algorandAssetRows(query, algorand, mode, openRoute)),
        section("Your Accounts", matching(query, accountEntries(view, openRoute)).rows()),
        section("Chains", matching(query, chainEntries(view, openRoute)).rows()),
    )
}

/**
 * The Algorand assets that [query] names on the side of [mode], as the Add asset screen finds them: the verified ones by ticker or ID, and the ID the
 * owner types. A row opens the asset on [account], which has a page for it even with no balance, so that Receive can offer the opt-in. No account, no rows.
 */
internal fun algorandAssetRows(query: String, account: WalletAccount?, mode: NetworkMode, openRoute: (String) -> Unit): List<SearchRow> {
    if (account == null) return emptyList()
    val network = if (mode.holds(ALGORAND_TESTNET.testnet)) ALGORAND_TESTNET else ALGORAND_MAINNET
    return AddAssetRows.rows(network, emptyList(), query).map { row ->
        SearchRow(
            key = "asa-${row.assetId}",
            title = row.title,
            detail = row.detail,
            onOpen = { openRoute(LumioRoute.asset(account.walletId, account.id, row.assetId.toString())) },
        )
    }
}

/** The address row of the Main tab: a whole address that is pasted opens the wallet the profile panel chose, when there is one. */
internal fun addressRows(query: String, wallet: PairedWallet?, openRoute: (String) -> Unit): List<SearchRow> =
    if (wallet != null && looksLikeAddress(query)) {
        listOf(SearchRow(key = "send-to-address", title = "Send to This Address", detail = groupAddress(query), onOpen = { openRoute(LumioRoute.wallet(wallet.id)) }))
    } else {
        emptyList()
    }

/** The ETH and SOL tabs: the coins and the accounts on the chains of [namespace], grouped under their own labels. */
private fun chainTabSections(query: String, view: WalletsView, namespace: String, openRoute: (String) -> Unit): List<SearchSection> = listOfNotNull(
    section("Tokens", matching(query, coinEntries(view, openRoute, namespace)).rows()),
    section("Your Accounts", matching(query, accountEntries(view, openRoute, namespace)).rows()),
)

/** A section of [rows] under [label], or null when there are no rows to show. */
private fun section(label: String?, rows: List<SearchRow>): SearchSection? = if (rows.isEmpty()) null else SearchSection(label, rows)

/** The trending coins that match [needle], or all of them when it is blank. A trending coin has no page yet, so its row opens nothing. */
private fun trendingRows(list: TrendingList?, needle: String, fiat: Fiat): List<SearchRow> = list?.coins.orEmpty()
    .filter { coin -> needle.isEmpty() || coin.name.contains(needle, ignoreCase = true) || coin.symbol.contains(needle, ignoreCase = true) }
    .mapIndexed { index, coin -> SearchRow(key = "$index-${coin.symbol}", title = coin.name, detail = trendingDetail(coin, fiat), onOpen = null) }

/** A trending coin's line: its ticker, its rank by market cap, its price and its 24 h change, each when the source gives it. */
private fun trendingDetail(coin: TrendingCoin, fiat: Fiat): String = listOfNotNull(
    coin.symbol,
    coin.rank?.let { "#$it" },
    coin.priceUsd?.let { fiat.amount(it) },
    coin.change24hPct?.let { changeText(it) },
).joinToString(" · ")

/** The search rows of these entries, each keyed by its position and opening its target. */
private fun List<SearchEntry<Open>>.rows(): List<SearchRow> = mapIndexed { index, entry ->
    SearchRow(key = "$index-${entry.title}", title = entry.title, detail = entry.detail, onOpen = entry.target)
}

/**
 * One entry per coin that an account holds, found by its name and its ticker. It opens the wallet that holds the coin. [namespace] keeps the coins on
 * the chains of that namespace only, and null keeps them all.
 */
private fun coinEntries(view: WalletsView, openRoute: (String) -> Unit, namespace: String? = null): List<SearchEntry<Open>> = view.wallets
    .flatMap { wallet -> view.accountsOf(wallet.id).mapNotNull { account -> account.balance?.let { wallet.id to it } } }
    .filter { (_, balance) -> balance.balance.signum() > 0 && (namespace == null || balance.network.chain.namespace == namespace) }
    .distinctBy { (_, balance) -> balance.assetId }
    .map { (walletId, balance) ->
        val coin = WatchedAsset(balance.network)
        val (symbol, name) = coinNames(coin)
        SearchEntry(title = name, detail = symbol, terms = listOf(name, symbol), target = { openRoute(LumioRoute.coin(walletId, coin)) })
    }

/**
 * One entry per account of every wallet, found by its network, its wallet's name and its address. [namespace] keeps the accounts of that namespace only,
 * and null keeps them all.
 */
private fun accountEntries(view: WalletsView, openRoute: (String) -> Unit, namespace: String? = null): List<SearchEntry<Open>> = view.wallets.flatMap { wallet ->
    view.accountsOf(wallet.id).filter { namespace == null || it.namespace == namespace }.map { account ->
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

/**
 * One entry per provider the Data providers page lists, found by its name. Its detail is its status, as the page words it, and it opens the provider's page.
 */
private fun providerEntries(lists: ProviderLists?, openRoute: (String) -> Unit): List<SearchEntry<Open>> {
    val rows = lists?.let { it.data + it.prices + it.rpc }.orEmpty()
    return rows.map { row ->
        val name = providerName(row.id)
        SearchEntry(
            title = name,
            detail = listOfNotNull(providerStatusWord(row.status), providerStatusNote(row.status)).joinToString(" · "),
            terms = listOf(name),
            target = { openRoute(LumioRoute.provider(row.id)) },
        )
    }
}

/** One entry per address-book entry of every wallet, found by its name, its label and its address. It opens the wallet's address book. */
private fun addressBookEntries(view: WalletsView, openRoute: (String) -> Unit): List<SearchEntry<Open>> = view.wallets.flatMap { wallet ->
    wallet.addressBook.map { entry ->
        SearchEntry(
            title = entry.name,
            detail = entry.label ?: groupAddress(entry.address),
            terms = listOfNotNull(entry.name, entry.label, entry.address),
            target = { openRoute(LumioRoute.addressBook(wallet.id)) },
        )
    }
}

/** One entry per watched asset of the wallet [walletId], found by its name and its ticker. No wallet means no watchlist. */
private fun watchEntries(assets: List<WatchedAsset>, walletId: String?, openRoute: (String) -> Unit): List<SearchEntry<Open>> {
    if (walletId == null) return emptyList()
    return assets.map { asset ->
        val (symbol, name) = coinNames(asset)
        SearchEntry(title = name, detail = symbol, terms = listOf(name, symbol), target = { openRoute(LumioRoute.coin(walletId, asset)) })
    }
}
