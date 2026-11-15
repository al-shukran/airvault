package app.lumio

import app.lensly.app.SessionAttachment
import app.lensly.core.vault.VaultSession
import app.lumio.btc.BtcStore
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.db.LumioDatabase
import app.lumio.data.scan.ScanRows

/**
 * What Lumio keeps for one open vault: the cache, the scan job rows, the last known balances and the provider settings. Every key in it comes
 * from [session], so when the vault locks, [app.lensly.app.VaultHolder] drops this object and nothing it holds can be read.
 */
class LumioVault(val session: VaultSession, database: LumioDatabase, clock: Clock = WallClock) : SessionAttachment {
    /** The cache of chain data. Its entries are sealed under keys derived from [session]. */
    val cache: Cache = Cache(database.kv(), CacheKeys.from(session), clock)

    /** The rows of the scan queue, sealed under a key derived from [session]. */
    val scanRows: ScanRows = ScanRows(database.scanJobs(), session.deriveKey(SCAN_JOB_PURPOSE))

    /** The last known balances, read from the cache. */
    val store: BtcStore = BtcStore(cache)

    @Volatile
    private var providerConfigs: Map<String, ProviderConfig> = ProviderConfigStore(session).load()

    /** The provider settings of this vault, as they were last saved or read. */
    fun configs(): Map<String, ProviderConfig> = providerConfigs

    /** Saves [configs] in place of the earlier settings. @throws IllegalStateException when the vault has closed. */
    fun saveConfigs(configs: Map<String, ProviderConfig>) {
        ProviderConfigStore(session).save(configs)
        providerConfigs = configs
    }

    private companion object {
        const val SCAN_JOB_PURPOSE = "lumio-scan-job-v1"
    }
}
