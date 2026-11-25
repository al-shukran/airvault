package app.lumio

import app.lensly.app.SessionAttachment
import app.lensly.core.vault.VaultSession
import app.lumio.btc.BtcStore
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.DisplaySettingsStore
import app.lumio.data.config.PreferRpcStore
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.config.TransactionSettings
import app.lumio.data.config.TransactionSettingsStore
import app.lumio.data.db.LumioDatabase
import app.lumio.data.scan.ScanRows
import app.lumio.data.tx.TxKeys
import app.lumio.data.tx.TxStore
import app.lumio.pair.FirstSyncImports
import app.lumio.pair.PairedWallets
import app.lumio.portfolio.ActivityPage
import app.lumio.portfolio.PortfolioBoard
import app.lumio.wallets.WalletsBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Lumio keeps for one open vault: the cache, the scan job rows, the last known balances and the provider settings. Every key in it comes
 * from [session], so when the vault locks, [app.lensly.app.VaultHolder] drops this object and nothing it holds can be read.
 */
class LumioVault(val session: VaultSession, database: LumioDatabase, clock: Clock = WallClock) : SessionAttachment {
    /** The cache of chain data. Its entries are sealed under keys derived from [session]. */
    val cache: Cache = Cache(database.kv(), CacheKeys.from(session), clock)

    /** The rows of the scan queue, sealed under a key derived from [session]. */
    val scanRows: ScanRows = ScanRows(database.scanJobs(), session.deriveKey(SCAN_JOB_PURPOSE))

    /** The transaction history of this vault: its rows, their search index and the comments, under keys derived from [session]. */
    val txs: TxStore = TxStore(database.txs(), TxKeys.from(session))

    /** The last known balances, read from the cache. */
    val store: BtcStore = BtcStore(cache)

    @Volatile
    private var providerConfigs: Map<String, ProviderConfig> = ProviderConfigStore(session).load()

    /** The provider settings of this vault, as they were last saved or read. */
    fun configs(): Map<String, ProviderConfig> = providerConfigs

    /** Saves [configs] in place of the earlier settings. @throws IllegalStateException when the vault has closed. */
    @Synchronized
    fun saveConfigs(configs: Map<String, ProviderConfig>) {
        ProviderConfigStore(session).save(configs)
        providerConfigs = configs
    }

    /**
     * Saves the settings that [change] makes of the current ones, in one write. The settings screen changes the enable switch, the priorities and the
     * API key through it. An API key is kept in the settings record with the rest, which the vault seals.
     */
    @Synchronized
    fun updateConfigs(change: (Map<String, ProviderConfig>) -> Map<String, ProviderConfig>) {
        saveConfigs(change(providerConfigs))
    }

    @Volatile
    private var preferRpcChoice: Boolean = PreferRpcStore.read(session)

    /** The owner's "Prefer RPC over Explorers" choice, as it was last saved or read. It can be read after the vault has closed. */
    fun preferRpc(): Boolean = preferRpcChoice

    /** Saves the "Prefer RPC over Explorers" choice. @throws IllegalStateException when the vault has closed. */
    @Synchronized
    fun savePreferRpc(on: Boolean) {
        PreferRpcStore.write(session, on)
        preferRpcChoice = on
    }

    private val displayState = MutableStateFlow(DisplaySettingsStore.read(session))

    /** The display settings of this vault, as they were last saved or read. Home collects it, and the settings screen changes it. */
    val display: StateFlow<DisplaySettings> = displayState.asStateFlow()

    /**
     * Saves the display settings that [change] makes of the current ones, in one write, and then publishes them to [display]. A write that fails
     * throws, and [display] keeps the settings it had.
     */
    @Synchronized
    fun updateDisplay(change: (DisplaySettings) -> DisplaySettings) {
        val next = change(displayState.value)
        DisplaySettingsStore.write(session, next)
        displayState.value = next
    }

    private val transactionState = MutableStateFlow(TransactionSettingsStore.read(session))

    /** The transaction settings of this vault, as they were last saved or read. Activity and the send flow collect it, and the settings screen changes it. */
    val transactions: StateFlow<TransactionSettings> = transactionState.asStateFlow()

    /**
     * Saves the transaction settings that [change] makes of the current ones, in one write, and then publishes them to [transactions]. A write that fails
     * throws, and [transactions] keeps the settings it had.
     */
    @Synchronized
    fun updateTransactions(change: (TransactionSettings) -> TransactionSettings) {
        val next = change(transactionState.value)
        TransactionSettingsStore.write(session, next)
        transactionState.value = next
    }

    /** The balances that the wallet screens of this vault share. [LumioApp] sets it as the vault opens, and [wipe] closes it when the vault locks. */
    internal var wallets: WalletsBoard? = null

    /** The balances and the activity of the Portfolio tab. [LumioApp] sets it with [wallets], and [wipe] closes it with them. */
    internal var portfolio: PortfolioBoard? = null

    /** The coin activity page read ahead of a push to the Asset page, see [CoinActivityPreload]. It belongs to this vault, so [wipe] drops it. */
    @Volatile
    internal var keptCoinActivity: Pair<CoinActivityKey, ActivityPage>? = null

    override fun wipe() {
        keptCoinActivity = null
        wallets?.close()
        wallets = null
        portfolio?.close()
        portfolio = null
        PairedWallets.forget(session)
    }

    /** The facts the Settings list shows, read when the vault opens and again when the list is shown. */
    internal val settingsFacts = SettingsFactsHolder(this)

    /** The imports whose first sync this vault has started, so returning to an import reattaches to its run. */
    internal val firstSyncs = FirstSyncImports()

    private companion object {
        const val SCAN_JOB_PURPOSE = "lumio-scan-job-v1"
    }
}
