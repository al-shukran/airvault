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
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.db.LumioDatabase
import app.lumio.data.scan.ScanRows
import app.lumio.data.tx.TxKeys
import app.lumio.data.tx.TxStore
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

    /** The balances that the wallet screens of this vault share. [LumioApp] sets it as the vault opens, and [wipe] closes it when the vault locks. */
    internal var wallets: WalletsBoard? = null

    override fun wipe() {
        wallets?.close()
        wallets = null
    }

    /** The facts the Settings list shows, read when the vault opens and again when the list is shown. */
    internal val settingsFacts = SettingsFactsHolder(this)

    private companion object {
        const val SCAN_JOB_PURPOSE = "lumio-scan-job-v1"
    }
}
