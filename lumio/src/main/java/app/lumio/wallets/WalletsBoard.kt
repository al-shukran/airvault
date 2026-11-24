package app.lumio.wallets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.airvault.core.model.ChainId
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.chains.ChainHolding
import app.lumio.data.WallClock
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.Kept
import app.lumio.data.price.Price
import app.lumio.data.price.PriceRepository
import app.lumio.data.price.PriceSeries
import app.lumio.data.provider.MarketQuote
import app.lumio.data.provider.TrendingList
import app.lumio.data.scan.ScanEvent
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.wallets.AssetRows
import app.lumio.watchlist.FIRST_RUN_ASSETS
import app.lumio.watchlist.WalletWatchlist
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notice a read that did not answer leaves on the wallet screens. The last known balances stay on them. */
private const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."

/**
 * What a read of the vault gives the screens: the balances of one side, the tokens of its Ethereum accounts with their dollars, and the market figures
 * the vault kept last: the watchlists of the shown wallets, the last prices of their assets, the whole market and the trending coins.
 */
private class Loaded(
    val view: WalletsView,
    val tokenCounts: Map<WalletAccount, Int>,
    val tokenUsd: Map<WalletAccount, Double>,
    val tokenHeld: Map<WalletAccount, List<TokenHolding>>,
    val watchlists: Map<String, WalletWatchlist>,
    val prices: Map<AssetId, Price>,
    val market: Kept<MarketQuote>?,
    val trending: Kept<TrendingList>?,
    val dayCharts: Map<AssetId, PriceSeries>,
    /** [WalletsBoard.forgets] when the read began. A wallet forgotten since may still be in the read's view and watchlists. */
    val forgets: Int,
)

/**
 * What an account's screen reads beyond its balance: its tokens, its holdings of other assets, the prices of its assets, the addresses its activity is read
 * for, and its activity. A part that is still null has not been read yet. The parts stay in the board for the visit and the next one, so an account that
 * opens again shows them at once.
 */
internal class AccountReads(namespace: String) {
    /** The ERC-20 balances of the Ethereum address. Empty for the other chains. Null until an Ethereum account's read lands. */
    var tokens by mutableStateOf<List<TokenHolding>?>(if (namespace == Networks.EIP155) null else emptyList())

    /** The SPL tokens of a Solana account, or the assets of an Algorand account. Empty for the other chains. Null until that read lands. */
    var holdings by mutableStateOf<List<ChainHolding>?>(
        if (namespace == Networks.SOLANA || namespace == Networks.ALGORAND) null else emptyList(),
    )

    /** The USD prices of the account's assets, merged over the reads. */
    var prices by mutableStateOf<Map<AssetId, Price>>(emptyMap())

    /** The newest rows of the account's activity. Null until its first read lands. */
    var activity by mutableStateOf<List<TxFeedItem>?>(null)

    /**
     * The addresses that the activity is read for: the Ethereum address, or the addresses that a Bitcoin account's scans found in use. A flow, since a
     * history read on a background thread turns up Bitcoin addresses.
     */
    val addresses = MutableStateFlow<Set<String>>(emptySet())
}

/**
 * The balances that the wallet screens of one open vault show, kept in memory for as long as the vault is open. The Wallets home reads the vault's caches
 * and refreshes the balances from the networks when it opens: once per unlock, after a return from the background, and after the side changes. It
 * refreshes again when the owner pulls to refresh. Each time the home shows a side, it also reads the other parts of each account's screen (see
 * [readAccounts]), so an Account screen pushed over the home shows its rows on its first frame. [close] drops it when the vault locks.
 */
internal class WalletsBoard(
    private val app: LumioApp,
    private val vault: LumioVault,
    /** The price source of the market figures, the watched prices and the day charts. Its cache is the open vault's; a test gives one over its own vault. */
    private val repository: PriceRepository = app.prices,
    /** The prices of [ids] that have one. The board reads each account's asset prices through it; a test stands in for the price sources. */
    private val readPrices: suspend (ids: Collection<AssetId>) -> Map<AssetId, Price> = { ids -> repository.assetPrices(ids) },
) {
    private val session: VaultSession = vault.session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var side: NetworkMode? = null
    private var version = Int.MIN_VALUE
    private var readJob: Job? = null
    private var refreshJob: Job? = null
    private var accountsJob: Job? = null
    private val accountReads = HashMap<String, AccountReads>()
    private val chartReads = mutableStateMapOf<AssetId, PriceSeries>()
    private val dayCharts = mutableStateMapOf<AssetId, PriceSeries>()
    private var marketJob: Job? = null

    /** Counts the forgotten watchlists. A read that began before a forget may hold the forgotten wallet, so [show] drops it. */
    private var forgets = 0

    /** The balances of the side, or null until its first read lands. */
    var view by mutableStateOf<WalletsView?>(null)
        private set

    /** The tokens each Ethereum account holds on the side, as the side's balances were read. */
    var tokenCounts by mutableStateOf<Map<WalletAccount, Int>>(emptyMap())
        private set

    /** The dollars of the tokens each account holds on the side, for the accounts whose tokens have a price. */
    var tokenUsd by mutableStateOf<Map<WalletAccount, Double>>(emptyMap())
        private set

    /** The token balances each Ethereum account holds on the side, as the Asset page reads a token's holdings from them. */
    var tokenHeld by mutableStateOf<Map<WalletAccount, List<TokenHolding>>>(emptyMap())
        private set

    /** True while a read of the networks runs. The freshness line says "Refreshing…" then. */
    var refreshing by mutableStateOf(false)
        private set

    /** True while the running refresh was started by the owner pulling. Only then does the pull indicator show. */
    var refreshByPull by mutableStateOf(false)
        private set

    /** True when the Wallets home should read the networks as it shows: after an unlock, a return from the background, or a change of side. */
    var refreshDue by mutableStateOf(true)
        private set

    /** The notice of a read that did not answer, or of a scan that failed, or null. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** The chains whose last read did not answer. Their accounts say so, and keep the balances they had. */
    var unreachable by mutableStateOf<Set<ChainId>>(emptySet())
        private set

    /** The time of the last complete read of the networks, or null before the first one. */
    var updatedAtMs by mutableStateOf<Long?>(null)
        private set

    /** The watchlist of each shown wallet, by wallet id. A wallet with no stored list has [FIRST_RUN_ASSETS]. */
    var watchlists by mutableStateOf<Map<String, WalletWatchlist>>(emptyMap())
        private set

    /** The USD price of each watched asset: the last one kept, then the one a source answers now. */
    var prices by mutableStateOf<Map<AssetId, Price>>(emptyMap())
        private set

    /** The whole market's figures, the last ones kept or the ones a source answered, or null before any. */
    var market by mutableStateOf<Kept<MarketQuote>?>(null)
        private set

    /** The trending coins, the last list kept or the one a source answered, or null before any. */
    var trending by mutableStateOf<Kept<TrendingList>?>(null)
        private set

    init {
        scope.launch {
            app.scanQueue.events.collect { event ->
                when (event) {
                    is ScanEvent.Finished -> side?.let { mode -> readOrNull { load(mode) }?.let { show(mode, it, withAccounts = false) } }
                    is ScanEvent.Failed -> notice = REFRESH_NOTICE
                    is ScanEvent.Progress -> Unit
                }
            }
        }
    }

    /**
     * Shows the balances of [mode] for the wallets' [listVersion]. It reads the vault's caches and no network for the balances; the accounts' other parts
     * follow as they are read. A change of side clears the balances of the side before it, and makes the next home open refresh.
     */
    fun showSide(mode: NetworkMode, listVersion: Int) {
        if (mode == side && listVersion == version) return
        if (mode != side) {
            refreshJob?.cancel()
            refreshJob = null
            endRefresh()
            view = null
            unreachable = emptySet()
            tokenCounts = emptyMap()
            tokenUsd = emptyMap()
            tokenHeld = emptyMap()
            refreshDue = true
        }
        side = mode
        version = listVersion
        readJob?.cancel()
        readJob = scope.launch {
            readOrNull { load(mode) }?.let { show(mode, it, withAccounts = true) }
        }
    }

    /**
     * Reads the networks of the side, then shows the balances they give. [pulled] is true when the owner pulled to start it. A refresh that runs already
     * is not started again, and a pull then shows the pull indicator for the one that runs.
     */
    fun refresh(pulled: Boolean = false) {
        refreshDue = false
        val mode = side ?: return
        if (refreshing) {
            if (pulled) refreshByPull = true
            return
        }
        refreshing = true
        refreshByPull = pulled
        notice = null
        refreshMarket()
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = WalletsLoader.refresh(app, session, mode)
                readOrNull { load(mode) }?.let { show(mode, it, withAccounts = true) }
                unreachable = result.unreachable
                if (result.failed) notice = REFRESH_NOTICE else updatedAtMs = WallClock.nowMs()
            } finally {
                if (refreshJob === currentCoroutineContext()[Job]) endRefresh()
            }
        }
        refreshJob = run
        run.start()
    }

    /** The app came back from the background. The Wallets home reads the networks again as it shows. */
    fun markRefreshDue() {
        refreshDue = true
    }

    /** What the account's screen reads beyond its balance, on [mode]'s side. Kept until the vault locks. */
    fun readsOf(account: WalletAccount, mode: NetworkMode): AccountReads =
        accountReads.getOrPut("${mode.name}/${account.walletId}/${account.id.caip10}") { AccountReads(account.namespace) }

    /** The price chart of [asset], once a read has landed. */
    fun chartOf(asset: AssetId): PriceSeries? = chartReads[asset]

    /** Keeps [series] as the price chart of [asset]. */
    fun putChart(asset: AssetId, series: PriceSeries) {
        chartReads[asset] = series
    }

    /** The day's price points of [asset] that Home charts, once a read has landed. They are kept apart from [chartOf], which holds the asset page's range. */
    fun dayChartOf(asset: AssetId): PriceSeries? = dayCharts[asset]

    /**
     * Reads the whole market's figures, the prices of the watched assets and their day charts from the sources. Each answer is kept by the price repository,
     * so the board shows the last one when a read fails. A read that starts while another runs replaces it.
     */
    fun refreshMarket() {
        marketJob?.cancel()
        marketJob = scope.launch { readMarket() }
    }

    /** Reads the trending coins from the sources, for the search sheet's Trending tab. */
    fun refreshTrending() {
        scope.launch {
            if (!withContext(Dispatchers.IO) { repository.servesTrending() }) trending = null
            readOrNull { withContext(Dispatchers.IO) { repository.trending() } }?.let { trending = it }
        }
    }

    /** Stores [watchlist] for [walletId] in the vault, shows it, and reads the prices of the assets it adds. */
    fun putWatchlist(walletId: String, watchlist: WalletWatchlist) {
        watchlists = watchlists + (walletId to watchlist)
        scope.launch(Dispatchers.IO) { WatchlistStore.write(session, walletId, watchlist) }
        refreshMarket()
    }

    /**
     * Drops the watchlist of [walletId] from memory when the wallet is deleted, so a wallet paired again under the same id starts from the vault's copy.
     * The deletion calls it from a background thread, so the change runs on the board's main scope, where the board's state is kept.
     */
    fun forgetWatchlist(walletId: String) {
        scope.launch {
            forgets++
            watchlists = watchlists - walletId
        }
    }

    /** One pass of [refreshMarket]: the market, the watched prices and their day charts. Each read runs off the main thread, and its answer lands here. */
    private suspend fun readMarket() {
        if (!withContext(Dispatchers.IO) { repository.servesMarket() }) market = null
        readOrNull { withContext(Dispatchers.IO) { repository.market() } }?.let { market = it }
        val assets = watchedAssets()
        readOrNull { withContext(Dispatchers.IO) { repository.assetPrices(assets) } }?.let { prices = prices + it }
        val days = readOrNull {
            withContext(Dispatchers.IO) {
                assets.mapNotNull { asset -> repository.chart(asset, ChartFrame.H1)?.takeIf { it.points.isNotEmpty() }?.let { asset to it } }.toMap()
            }
        }
        days?.let { dayCharts.putAll(it) }
    }

    /**
     * The assets the watchlists of every paired wallet watch, each once. The vault is read rather than the board's view, so a read that starts before the
     * balances land still covers the lists.
     */
    private suspend fun watchedAssets(): List<AssetId> = withContext(Dispatchers.IO) {
        assetsOf(watchlistsOf(PairedWallets.list(session)))
    }

    /** The watchlist of each of [wallets], as the vault keeps it, or the first-run list for a wallet that has none. It reads the vault, so run it off the main thread. */
    private fun watchlistsOf(wallets: List<PairedWallet>): Map<String, WalletWatchlist> = wallets.associate { wallet ->
        wallet.id to (WatchlistStore.read(session, wallet.id) ?: WalletWatchlist(assets = FIRST_RUN_ASSETS))
    }

    /** The assets that [watchlists] watch, each once. */
    private fun assetsOf(watchlists: Map<String, WalletWatchlist>): List<AssetId> =
        watchlists.values.flatMap { list -> list.assets.map(WatchedAsset::assetId) }.distinct()

    /** Stops the reads and drops the balances. [LumioVault] calls it when the vault locks. */
    fun close() {
        scope.cancel()
        refreshJob = null
        readJob = null
        accountsJob = null
        side = null
        view = null
        tokenCounts = emptyMap()
        tokenUsd = emptyMap()
        tokenHeld = emptyMap()
        accountReads.clear()
        chartReads.clear()
        dayCharts.clear()
        marketJob?.cancel()
        marketJob = null
        watchlists = emptyMap()
        prices = emptyMap()
        market = null
        trending = null
        endRefresh()
    }

    private fun endRefresh() {
        refreshing = false
        refreshByPull = false
    }

    /**
     * Shows [loaded] when [mode] is the side the board shows. [withAccounts] reads the other parts of each account again, after the balances are shown, as
     * a home open or a refresh does; a scan that finishes alone does not, since the refresh that started it reads them once it has ended.
     */
    private fun show(mode: NetworkMode, loaded: Loaded, withAccounts: Boolean) {
        // A read that began before a wallet was forgotten may still list it. The list change that forgot it reads again, so this read is dropped.
        if (mode != side || loaded.forgets != forgets) return
        view = loaded.view
        tokenCounts = loaded.tokenCounts
        tokenUsd = loaded.tokenUsd
        tokenHeld = loaded.tokenHeld
        // A list or price read since the vault was read is newer than the vault, so it wins.
        watchlists = loaded.watchlists + watchlists
        prices = loaded.prices + prices
        market = loaded.market ?: market
        trending = loaded.trending ?: trending
        // A day chart read since the vault was read is newer than the vault's copy, so it wins.
        dayCharts.putAll(loaded.dayCharts.filterKeys { it !in dayCharts })
        if (withAccounts) {
            accountsJob?.cancel()
            accountsJob = scope.launch { readAccounts(mode, loaded.view) }
        }
    }

    /**
     * Reads each account of [shown] into its kept reads: its tokens, its holdings, the prices of its assets, the addresses its activity is read for and its
     * newest rows. The vault's caches answer first. The holdings and the prices may read the networks, and a read that fails keeps the part it had, or
     * shows none. An account's screen shows what has landed, and its rows update in place as the rest lands.
     */
    private suspend fun readAccounts(mode: NetworkMode, shown: WalletsView) {
        for (wallet in shown.wallets) {
            for (account in shown.accountsOf(wallet.id)) readAccount(mode, account)
        }
    }

    private suspend fun readAccount(mode: NetworkMode, account: WalletAccount) {
        val reads = readsOf(account, mode)
        reads.tokens = readOrNull { withContext(Dispatchers.IO) { AccountReadsLoader.tokensOf(app, session, mode, account) } }
            ?: reads.tokens ?: emptyList()
        reads.holdings = readOrNull { withContext(Dispatchers.IO) { AccountReadsLoader.holdingsOf(app, session, mode, account) } }
            ?: reads.holdings ?: emptyList()
        val ids = AssetRows.pricedIds(account, reads.tokens.orEmpty())
        if (ids.isNotEmpty()) {
            reads.prices = reads.prices + readOrNull { withContext(Dispatchers.IO) { readPrices(ids) } }.orEmpty()
        }
        readOrNull { withContext(Dispatchers.IO) { AccountReadsLoader.addressesOf(vault, session, mode, account) } }
            ?.let { found -> reads.addresses.update { known -> known + found } }
        val addresses = reads.addresses.value
        if (addresses.isEmpty()) return
        val networks = withContext(Dispatchers.IO) { AccountReadsLoader.planOf(app, session, mode, account).networks }
        readOrNull { withContext(Dispatchers.IO) { AccountReadsLoader.activityOf(app, vault, addresses, networks) } }
            ?.let { reads.activity = it }
    }

    /**
     * The balances of [mode] from the vault's caches, and the tokens of its Ethereum accounts with the dollars of the ones that have a price. It reads
     * no balance from the network.
     */
    private suspend fun load(mode: NetworkMode): Loaded {
        val epoch = forgets
        val view = WalletsLoader.read(app, session, mode)
        val held = heldTokensOf(view, mode)
        val tokenPrices = readPrices(held.values.flatMap { AssetRows.tokenIds(it) })
        val watchlists = withContext(Dispatchers.IO) { watchlistsOf(view.wallets) }
        val (prices, market, trending) = withContext(Dispatchers.IO) {
            Triple(repository.lastPrices(assetsOf(watchlists)), repository.lastMarket(), repository.lastTrending())
        }
        val dayCharts = withContext(Dispatchers.IO) { repository.lastDayCharts(assetsOf(watchlists)) }
        return Loaded(
            view,
            tokenCounts = held.mapValues { (_, tokens) -> tokens.count { it.amount.signum() > 0 } },
            tokenUsd = held.mapNotNull { (account, tokens) -> AssetRows.tokensUsd(tokens, tokenPrices)?.let { account to it } }.toMap(),
            tokenHeld = held,
            watchlists = watchlists,
            prices = prices,
            market = market,
            trending = trending,
            dayCharts = dayCharts,
            forgets = epoch,
        )
    }

    private suspend fun heldTokensOf(view: WalletsView, mode: NetworkMode): Map<WalletAccount, List<TokenHolding>> = withContext(Dispatchers.IO) {
        val accounts = view.wallets.flatMap { view.accountsOf(it.id) }.filter { it.namespace == Networks.EIP155 }
        val settings = EvmSettingsStore.read(session)
        val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
        val cache = TokenBalanceCache.read(session)
        accounts.associateWith { account ->
            TokenPortfolio.holdingsAt(account.walletId, account.address, chains, app.tokensFor(session), cache)
        }
    }
}
