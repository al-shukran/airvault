package app.lumio.portfolio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.bitcoinRefs
import app.lumio.btc.networkId
import app.lumio.chains.NativeBalanceCache
import app.lumio.chains.NativeBalances
import app.lumio.coinNames
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.TxAssetRef
import app.lumio.data.tx.TxQuery
import app.lumio.data.tx.TxSort
import app.lumio.evm.BalanceCache
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.evm.Portfolio
import app.lumio.home.HomeAsset
import app.lumio.home.HomeAssets
import app.lumio.home.HomeBalances
import app.lumio.home.TokenAsset
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import app.lumio.pair.ownAccountsOf
import app.lumio.pair.plainNames
import app.lumio.rowsOf
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.ActivitySort
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.assetIdOf
import app.lumio.transactions.chainLabel
import app.lumio.transactions.evmNetwork
import app.lumio.transactions.txDetailOf
import app.lumio.transactions.vaultChains
import app.lumio.watchlist.WatchedAsset
import app.lensly.app.addressKeyOf
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The first page of the activity on one side, newest or largest first. [names] names the parties of its rows, by [addressKeyOf]. */
class ActivityPage(val items: List<TxFeedItem>, val spamHidden: Int, val names: Map<String, String>)

/**
 * The balances and the activity of the open vault on one side. Both are read from the vault's caches first and priced with the last prices kept, so
 * the last read is on the screen at once: a page that opens from them has its rows on its first frame. The prices that the sources answer replace the
 * kept ones when they land. A change of side clears what the side before it showed.
 */
internal class PortfolioBoard(private val app: LumioApp, private val vault: LumioVault) {
    private val session: VaultSession = vault.session
    private var scope: PortfolioScope? = null

    /** The holdings of the scope, or null until its first read lands. */
    var holdings by mutableStateOf<List<PortfolioHolding>?>(null)
        private set

    /** The first page of the activity of the scope under the filter it was read with, or null until it lands. */
    var activity by mutableStateOf<ActivityPage?>(null)
        private set

    /** The transaction that was read last, for the transaction page that opens it. */
    private val transactions = LastTransaction()

    /**
     * Reads the transaction [txId] of [account] as its page shows it, and keeps it for that page. The row that opens the page calls this before it pushes
     * the page, so the first frame of the page has the transaction. Returns null when the store does not hold it.
     */
    suspend fun readTransaction(walletId: String, account: String, txId: String): TransactionPage? {
        val read = PortfolioReads.transaction(app, vault, walletId, account, txId) ?: return null
        transactions.put(read)
        return read
    }

    /** The transaction that [readTransaction] read last for [account] and [txId], or null when it was not read. */
    fun transactionOf(account: String, txId: String): TransactionPage? = transactions.get(account, txId)

    /**
     * Reads the holdings of the wallet [walletId] on [mode]'s side, or of every wallet when it is null. A change of scope clears the holdings of the scope
     * before it first.
     */
    suspend fun readHoldings(mode: NetworkMode, walletId: String?) {
        val now = PortfolioScope(mode, walletId)
        if (now != scope) reset(now)
        // The last prices kept show the rows at once, and the sources' answer replaces them when it lands, so the tab never waits for the network.
        val last = PortfolioReads.holdings(app, vault, mode, walletId, live = false)
        if (now == scope) holdings = last
        val read = PortfolioReads.holdings(app, vault, mode, walletId, live = true)
        if (now == scope) holdings = read
    }

    /** Reads the first page of the activity of the scope under [filter], as [readHoldings] scopes it. */
    suspend fun readActivity(mode: NetworkMode, walletId: String?, filter: ActivityFilter, includeSpam: Boolean) {
        val now = PortfolioScope(mode, walletId)
        if (now != scope) reset(now)
        val last = PortfolioReads.activity(app, vault, mode, walletId, filter, includeSpam, live = false)
        if (now == scope) activity = last
        val read = PortfolioReads.activity(app, vault, mode, walletId, filter, includeSpam, live = true)
        if (now == scope) activity = read
    }

    /** Drops what the board shows. [LumioVault] calls it when the vault locks. */
    fun close() {
        scope = null
        holdings = null
        activity = null
        transactions.clear()
    }

    private fun reset(now: PortfolioScope) {
        scope = now
        holdings = null
        activity = null
    }
}

/** What a board read covers: the side, and the one wallet of it, or every wallet when [walletId] is null. */
private data class PortfolioScope(val mode: NetworkMode, val walletId: String?)

/** The reads behind [PortfolioBoard]: each one reads the caches of the vault on the I/O dispatcher. */
internal object PortfolioReads {
    /** The rows of activity that one page holds. */
    const val PAGE = 100

    /**
     * The holdings of [mode]'s side of [walletId], or of every wallet when it is null, with their prices: the last prices kept in the vault, or the
     * sources' answer when [live] is set, which waits for the network. A price that is not known leaves the value out, never zero.
     */
    suspend fun holdings(app: LumioApp, vault: LumioVault, mode: NetworkMode, walletId: String?, live: Boolean = true): List<PortfolioHolding> = withContext(Dispatchers.IO) {
        val session = vault.session
        val wallets = PairedWallets.list(session).filter { walletId == null || it.id == walletId }
        val settings = EvmSettingsStore.read(session)
        val chains = evmChains(app, settings, mode)
        val count = settings.addressCount
        val tokensOf = app.tokensFor(session)
        val refs = bitcoinRefs(session).filter { mode.holds(it.account.network) && (walletId == null || it.walletId == walletId) }
        val natives = HomeBalances.evm(chains, Portfolio.holdings(wallets, count, chains, BalanceCache.read(session))) +
            HomeBalances.bitcoin(rowsOf(vault, refs)) +
            HomeBalances.nativeCoins(wallets, NativeBalanceCache.read(session), NativeBalances.coinsOf(mode))
        val owned = CustomTokenStore.read(session)
        val cache = TokenBalanceCache.read(session)
        val titles = HashMap<AssetId, String>()
        val tokens = wallets.flatMap { TokenPortfolio.holdings(it, count, chains, tokensOf, cache) }.map { holding ->
            val network = evmNetwork(holding.chain)
            val contract = holding.token.contract
            TokenAsset(
                id = TokenAsset.idOf(network, contract),
                symbol = holding.token.symbol,
                name = holding.chain.name,
                network = network,
                contract = contract,
                balance = BigDecimal(holding.amount).movePointLeft(holding.token.decimals),
                known = app.isKnownToken(network, contract),
                custom = CustomTokenStore.holds(owned, network, contract),
            ).also { titles[it.assetId] = holding.token.name }
        }
        val assets: List<HomeAsset> = natives + tokens
        val ids = assets.map { it.assetId }.distinct()
        val prices = if (live) app.prices.assetPrices(ids) else app.prices.lastPrices(ids)
        HomeAssets.priced(assets, prices).map { asset -> holdingOf(asset, titles[asset.assetId]) }
    }

    /**
     * The first page of the activity of [mode]'s side of [walletId], or of every wallet when it is null, under [filter], with the spam it hides counted
     * when [includeSpam] is off. Its dollar values use the last prices kept in the vault, or the sources' answer when [live] is set, which waits for the
     * network.
     */
    suspend fun activity(
        app: LumioApp,
        vault: LumioVault,
        mode: NetworkMode,
        walletId: String?,
        filter: ActivityFilter,
        includeSpam: Boolean,
        live: Boolean = true,
    ): ActivityPage =
        withContext(Dispatchers.IO) {
            val session = vault.session
            val every = PairedWallets.list(session)
            val wallets = every.filter { walletId == null || it.id == walletId }
            val settings = EvmSettingsStore.read(session)
            val chains = evmChains(app, settings, mode)
            val refs = bitcoinRefs(session).filter { mode.holds(it.account.network) && (walletId == null || it.walletId == walletId) }
            val accounts = wallets.flatMap { it.evmAddresses.take(settings.addressCount) }.toSet() +
                refs.map { it.account.receiveAddress(0) }
            val networks = (chains.map { evmNetwork(it) } + refs.map { it.account.network.networkId() })
                .filter { filter.chain == null || ChainFamily.of(it) == filter.chain }
                .toSet()
            // The names come from every wallet, so a party that another wallet's address book names keeps its name.
            val names = partyNames(every)
            if (accounts.isEmpty() || networks.isEmpty()) return@withContext ActivityPage(emptyList(), 0, names)
            val query = TxQuery(
                accounts = accounts,
                networks = networks,
                assets = filter.coin?.let { coinRefsOf(it, networks) }.orEmpty(),
                includeSpam = includeSpam,
                sort = if (filter.sort == ActivitySort.NEWEST) TxSort.TIMESTAMP else TxSort.AMOUNT,
            )
            val store = vault.txs
            val chainNames = vaultChains(app, vault)
            val rows = store.page(query, 0, PAGE)
            val ids = rows.map { assetIdOf(it) }.distinct()
            val prices = if (live) app.prices.assetPrices(ids) else app.prices.lastPrices(ids)
            val items = rows.map { tx ->
                val price = prices[assetIdOf(tx)]
                TxFeedItem(
                    tx = tx,
                    usd = price?.let { BigDecimal(tx.amount).movePointLeft(tx.asset.decimals).toDouble() * it.usd },
                    chain = chainLabel(tx.network, chainNames),
                )
            }
            val hidden = if (includeSpam) 0 else store.count(query.copy(includeSpam = true)) - store.count(query)
            ActivityPage(items, hidden, names)
        }

    /**
     * The assets that [coin] names on [networks]. A token is its contract on its network. A native coin is the native coin of every network whose coin
     * has its symbol, so the activity of ETH covers each chain's ETH, as the holdings of the coin do.
     */
    private fun coinRefsOf(coin: WatchedAsset, networks: Set<NetworkId>): Set<TxAssetRef> {
        coin.contract?.let { return setOf(TxAssetRef(coin.network, it)) }
        val symbol = coinNames(coin).first
        return networks.filter { coinNames(WatchedAsset(it)).first == symbol }.map { TxAssetRef(it) }.toSet()
    }

    /** The transaction [txId] of [account] with the wallet's own accounts and address book, or null when the store does not hold it. */
    suspend fun transaction(app: LumioApp, vault: LumioVault, walletId: String, account: String, txId: String): TransactionPage? =
        withContext(Dispatchers.IO) {
            val detail = txDetailOf(app, vault, account, txId) ?: return@withContext null
            val wallets = PairedWallets.list(vault.session)
            val wallet = wallets.firstOrNull { it.id == walletId }
            TransactionPage(detail, ownAccountsOf(wallets), wallet, wallet?.addressBook.orEmpty())
        }

    /** The EVM chains of [mode]'s side that the owner has turned on. */
    private fun evmChains(app: LumioApp, settings: EvmSettings, mode: NetworkMode): List<EvmChain> =
        EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }

    /** The names of the parties that the address book and the wallets' own accounts name, by their key. */
    fun partyNames(wallets: List<PairedWallet>): Map<String, String> {
        val book = wallets.flatMap { it.addressBook }.associate { addressKeyOf(it.address) to it.name }
        val own = ownAccountsOf(wallets).plainNames()
        return own + book
    }
}
