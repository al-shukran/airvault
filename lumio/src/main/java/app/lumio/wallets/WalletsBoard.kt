package app.lumio.wallets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lensly.core.vault.VaultSession
import app.lumio.LumioApp
import app.lumio.chains.ChainHolding
import app.lumio.data.WallClock
import app.lumio.data.asset.AssetId
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The notice a read that did not answer leaves on the wallet screens. The last known balances stay on them. */
private const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."

/** What a read of the vault gives the screens: the balances of one side, and the tokens of its Ethereum accounts. */
private class Loaded(val view: WalletsView, val tokenCounts: Map<WalletAccount, Int>)

/**
 * What an account's screen reads beyond its balance: its tokens, its holdings of other assets, the prices of its assets and its activity. A part that is
 * still null has not been read yet. The parts stay in the board for the visit and the next one, so an account that opens again shows them at once.
 */
internal class AccountReads(chain: AccountChain) {
    /** The ERC-20 balances of the Ethereum address. Empty for the other chains. Null until an Ethereum account's read lands. */
    var tokens by mutableStateOf<List<TokenHolding>?>(if (chain == AccountChain.ETHEREUM) null else emptyList())

    /** The SPL tokens of a Solana account, or the assets of an Algorand account. Empty for the other chains. Null until that read lands. */
    var holdings by mutableStateOf<List<ChainHolding>?>(
        if (chain == AccountChain.SOLANA || chain == AccountChain.ALGORAND) null else emptyList(),
    )

    /** The USD prices of the account's assets, merged over the reads. */
    var prices by mutableStateOf<Map<AssetId, Price>>(emptyMap())

    /** The newest rows of the account's activity. Null until its first read lands. */
    var activity by mutableStateOf<List<TxFeedItem>?>(null)
}

/**
 * The balances that the wallet screens of one open vault show, kept in memory for as long as the vault is open. The Wallets home reads the vault's caches
 * and refreshes the balances from the networks when it opens: once per unlock, after a return from the background, and after the side changes. It
 * refreshes again when the owner pulls to refresh. The screens pushed over the home read this board and start no refresh, so their first frame shows
 * what the board already holds. [close] drops it when the vault locks.
 */
internal class WalletsBoard(private val app: LumioApp, private val session: VaultSession) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var side: NetworkMode? = null
    private var version = Int.MIN_VALUE
    private var readJob: Job? = null
    private var refreshJob: Job? = null
    private val accountReads = HashMap<String, AccountReads>()
    private val chartReads = mutableStateMapOf<AssetId, PriceSeries>()

    /** The balances of the side, or null until its first read lands. */
    var view by mutableStateOf<WalletsView?>(null)
        private set

    /** The tokens each Ethereum account holds on the side, as the side's balances were read. */
    var tokenCounts by mutableStateOf<Map<WalletAccount, Int>>(emptyMap())
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
                    is ScanEvent.Finished -> side?.let { mode -> readOrNull { load(mode) }?.let { show(mode, it) } }
                    is ScanEvent.Failed -> notice = REFRESH_NOTICE
                    is ScanEvent.Progress -> Unit
                }
            }
        }
    }

    /**
     * Shows the balances of [mode] for the wallets' [listVersion]. It reads the vault's caches and no network. A change of side clears the balances of the
     * side before it, and makes the next home open refresh.
     */
    fun showSide(mode: NetworkMode, listVersion: Int) {
        if (mode == side && listVersion == version) return
        if (mode != side) {
            refreshJob?.cancel()
            refreshJob = null
            endRefresh()
            view = null
            tokenCounts = emptyMap()
            refreshDue = true
        }
        side = mode
        version = listVersion
        readJob?.cancel()
        readJob = scope.launch {
            readOrNull { load(mode) }?.let { show(mode, it) }
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
                readOrNull { load(mode) }?.let { show(mode, it) }
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
        accountReads.getOrPut("${mode.name}/${account.walletId}/${account.chain.name}/${account.index}") { AccountReads(account.chain) }

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
        side = null
        view = null
        tokenCounts = emptyMap()
        accountReads.clear()
        chartReads.clear()
        endRefresh()
    }

    private fun endRefresh() {
        refreshing = false
        refreshByPull = false
    }

    private fun show(mode: NetworkMode, loaded: Loaded) {
        if (mode != side) return
        view = loaded.view
        tokenCounts = loaded.tokenCounts
    }

    /** The balances of [mode] from the vault's caches, and the tokens of its Ethereum accounts. It reads no balance from the network. */
    private suspend fun load(mode: NetworkMode): Loaded {
        val view = WalletsLoader.read(app, session, mode)
        return Loaded(view, tokenCountsOf(view, mode))
    }

    private suspend fun tokenCountsOf(view: WalletsView, mode: NetworkMode): Map<WalletAccount, Int> = withContext(Dispatchers.IO) {
        val accounts = view.wallets.flatMap { view.accountsOf(it.id) }.filter { it.chain == AccountChain.ETHEREUM }
        val settings = EvmSettingsStore.read(session)
        val chains = EvmCatalog.chains(app.registry, settings).filter { it.chainId in settings.enabled && mode.holds(it) }
        val cache = TokenBalanceCache.read(session)
        accounts.associateWith { account ->
            TokenPortfolio.holdingsAt(account.walletId, account.address, chains, app.tokensFor(session), cache).count { it.amount.signum() > 0 }
        }
    }
}
