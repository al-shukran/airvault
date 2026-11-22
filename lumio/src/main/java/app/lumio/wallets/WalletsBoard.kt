package app.lumio.wallets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.LumioVault
import app.lumio.chains.ChainHolding
import app.lumio.data.WallClock
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.scan.ScanEvent
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.tokens.TokenBalanceCache
import app.lumio.tokens.TokenHolding
import app.lumio.tokens.TokenPortfolio
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.wallets.AssetRows
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

/** What a read of the vault gives the screens: the balances of one side, and the tokens of its Ethereum accounts. */
private class Loaded(val view: WalletsView, val tokenCounts: Map<WalletAccount, Int>, val tokenUsd: Map<WalletAccount, Double>)

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
    /** The prices of [ids] that have one. The board reads each account's asset prices through it; a test stands in for the price sources. */
    private val readPrices: suspend (ids: Collection<AssetId>) -> Map<AssetId, Price> = { ids -> app.prices.assetPrices(ids) },
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

    /** The balances of the side, or null until its first read lands. */
    var view by mutableStateOf<WalletsView?>(null)
        private set

    /** The tokens each Ethereum account holds on the side, as the side's balances were read. */
    var tokenCounts by mutableStateOf<Map<WalletAccount, Int>>(emptyMap())
        private set

    /** The dollars of the tokens each account holds on the side, for the accounts whose tokens have a price. */
    var tokenUsd by mutableStateOf<Map<WalletAccount, Double>>(emptyMap())
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

    /** The notice of a read that did not answer, or null. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** The time of the last complete read of the networks, or null before the first one. */
    var updatedAtMs by mutableStateOf<Long?>(null)
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
            tokenCounts = emptyMap()
            tokenUsd = emptyMap()
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
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val failed = WalletsLoader.refresh(app, session, mode)
                readOrNull { load(mode) }?.let { show(mode, it, withAccounts = true) }
                if (failed) notice = REFRESH_NOTICE else updatedAtMs = WallClock.nowMs()
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
        accountReads.clear()
        chartReads.clear()
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
        if (mode != side) return
        view = loaded.view
        tokenCounts = loaded.tokenCounts
        tokenUsd = loaded.tokenUsd
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
        val view = WalletsLoader.read(app, session, mode)
        val held = heldTokensOf(view, mode)
        val prices = readPrices(held.values.flatMap { AssetRows.tokenIds(it) })
        return Loaded(
            view,
            tokenCounts = held.mapValues { (_, tokens) -> tokens.count { it.amount.signum() > 0 } },
            tokenUsd = held.mapNotNull { (account, tokens) -> AssetRows.tokensUsd(tokens, prices)?.let { account to it } }.toMap(),
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
