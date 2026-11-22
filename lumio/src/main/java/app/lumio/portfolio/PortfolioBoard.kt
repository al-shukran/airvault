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
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
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
import app.lumio.rowsOf
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.ActivityFilter
import app.lumio.transactions.ActivitySort
import app.lumio.transactions.TxFeedItem
import app.lumio.transactions.assetIdOf
import app.lumio.transactions.txDetailOf
import app.lumio.transactions.chainLabel
import app.lumio.transactions.evmNetwork
import app.lumio.transactions.vaultChains
import app.lensly.app.addressKeyOf
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The first page of the activity on one side, newest or largest first. [names] names the parties of its rows, by [addressKeyOf]. */
class ActivityPage(val items: List<TxFeedItem>, val spamHidden: Int, val names: Map<String, String>)

/**
 * The balances and the activity of the open vault on one side. Both are read from the vault's caches and no network, so the last read is on the
 * screen at once: a page that opens from them has its rows on its first frame. A change of side clears what the side before it showed.
 */
internal class PortfolioBoard(private val app: LumioApp, private val vault: LumioVault) {
    private val session: VaultSession = vault.session
    private var side: NetworkMode? = null

    /** The holdings of the side, or null until its first read lands. */
    var holdings by mutableStateOf<List<PortfolioHolding>?>(null)
        private set

    /** The first page of the activity of the side under the filter it was read with, or null until it lands. */
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

    /** Reads the holdings of [mode]'s side. A change of side clears the holdings of the side before it first. */
    suspend fun readHoldings(mode: NetworkMode) {
        if (mode != side) reset(mode)
        val read = PortfolioReads.holdings(app, vault, mode)
        if (mode == side) holdings = read
    }

    /** Reads the first page of the activity of [mode]'s side under [filter]. */
    suspend fun readActivity(mode: NetworkMode, filter: ActivityFilter, includeSpam: Boolean) {
        if (mode != side) reset(mode)
        val read = PortfolioReads.activity(app, vault, mode, filter, includeSpam)
        if (mode == side) activity = read
    }

    /** Drops what the board shows. [LumioVault] calls it when the vault locks. */
    fun close() {
        side = null
        holdings = null
        activity = null
        transactions.clear()
    }

    private fun reset(mode: NetworkMode) {
        side = mode
        holdings = null
        activity = null
    }
}

/** The reads behind [PortfolioBoard]: each one reads the caches of the vault on the I/O dispatcher. */
internal object PortfolioReads {
    /** The rows of activity that one page holds. */
    const val PAGE = 100

    /** The holdings of [mode]'s side, with their prices. A price that is not known leaves the value out, never zero. */
    suspend fun holdings(app: LumioApp, vault: LumioVault, mode: NetworkMode): List<PortfolioHolding> = withContext(Dispatchers.IO) {
        val session = vault.session
        val wallets = PairedWallets.list(session)
        val settings = EvmSettingsStore.read(session)
        val chains = evmChains(app, settings, mode)
        val count = settings.addressCount
        val tokensOf = app.tokensFor(session)
        val refs = bitcoinRefs(session).filter { mode.holds(it.account.network) }
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
        val prices = app.prices.assetPrices(assets.map { it.assetId }.distinct())
        HomeAssets.priced(assets, prices).map { asset -> holdingOf(asset, titles[asset.assetId]) }
    }

    /** The first page of the activity of [mode]'s side under [filter], with the spam it hides counted when [includeSpam] is off. */
    suspend fun activity(app: LumioApp, vault: LumioVault, mode: NetworkMode, filter: ActivityFilter, includeSpam: Boolean): ActivityPage =
        withContext(Dispatchers.IO) {
            val session = vault.session
            val wallets = PairedWallets.list(session)
            val settings = EvmSettingsStore.read(session)
            val chains = evmChains(app, settings, mode)
            val refs = bitcoinRefs(session).filter { mode.holds(it.account.network) }
            val accounts = wallets.flatMap { it.evmAddresses.take(settings.addressCount) }.toSet() +
                refs.map { it.account.receiveAddress(0) }
            val networks = (chains.map { evmNetwork(it) } + refs.map { it.account.network.networkId() })
                .filter { filter.chain == null || ChainFamily.of(it) == filter.chain }
                .toSet()
            val names = partyNames(wallets)
            if (accounts.isEmpty() || networks.isEmpty()) return@withContext ActivityPage(emptyList(), 0, names)
            val query = TxQuery(
                accounts = accounts,
                networks = networks,
                includeSpam = includeSpam,
                sort = if (filter.sort == ActivitySort.NEWEST) TxSort.TIMESTAMP else TxSort.AMOUNT,
            )
            val store = vault.txs
            val chainNames = vaultChains(app, vault)
            val rows = store.page(query, 0, PAGE)
            val prices = app.prices.assetPrices(rows.map { assetIdOf(it) }.distinct())
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
    private fun partyNames(wallets: List<PairedWallet>): Map<String, String> {
        val book = wallets.flatMap { it.addressBook }.associate { addressKeyOf(it.address) to it.name }
        val own = ownAccountsOf(wallets).mapValues { (_, account) -> account.displayName ?: account.autoName }
        return own + book
    }
}
