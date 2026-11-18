package app.lumio

import android.app.Application
import android.util.Log
import app.lensly.app.CoverApplication
import app.lensly.app.EntryGestureHolder
import app.lensly.app.EntryGestureStore
import app.lensly.app.SessionAttachment
import app.lensly.app.VaultHolder
import app.lensly.app.logBackupExclusion
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultSession
import app.lumio.btc.AddressStatsCache
import app.lumio.btc.networkId
import app.lumio.btc.scanJob
import app.lumio.chains.NativeBalances
import app.lumio.data.MonotonicClock
import app.lumio.data.WallClock
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.config.PreferRpcStore
import app.lumio.data.db.LumioDatabase
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.CustomEsploraProvider
import app.lumio.data.esplora.EsploraProtocol
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.price.PriceRepository
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxHistory
import app.lumio.data.tx.TxSpam
import app.lumio.data.provider.Http
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.blockchair.BlockchairProvider
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.scan.QueuedJob
import app.lumio.data.scan.ScanQueue
import app.lumio.data.scan.ScanRunner
import app.lumio.data.sched.Scheduler
import app.lumio.evm.EvmCatalog
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmReads
import app.lumio.evm.EvmRpc
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkModeStore
import app.lumio.tokens.CustomTokenStore
import app.lumio.tokens.Token
import app.lumio.tokens.TokenCatalog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Lumio's application object. It owns the holders that the shared cover and gate use, the chain registry, and the RPC client. Lumio holds no key
 * material. Its vault is the App Password gate, and it keeps no wallet secret. The Bitcoin data path is one scheduler over the Esplora providers,
 * one scan queue and one scan runner, all shared by every screen.
 */
class LumioApp : Application(), CoverApplication {
    override val vaultHolder: VaultHolder by lazy { VaultHolder(Vault(this), attach = ::openVault) }
    override val entryGestureHolder: EntryGestureHolder by lazy { EntryGestureHolder(EntryGestureStore.open(this)) }

    /** The lock choices of Settings → Security. They sit outside the vault, because the cover reads them before the vault opens. */
    val lockSettings: LockSettingsStore by lazy { LockSettingsStore.open(this) }

    /** The chain registry, read from the catalogs in this APK. Debug builds include the debug testnets. */
    val registry: Registry by lazy { RegistryLoader.fromAssets(assets) }

    /** The five major tokens of each mainnet chain, from the bundled list `assets/tokens/default-tokens.json`. */
    val defaultTokens: List<Token> by lazy { TokenCatalog.parse(assets.open("tokens/default-tokens.json").bufferedReader().use { it.readText() }) }

    /** The HTTP stack that every RPC call shares: no redirects, and each call is bounded by a timeout. */
    val rpcHttp: Http by lazy { JsonRpc.newHttp() }

    /** The timers of the scheduler's waiting calls. They live as long as the app. */
    private val rpcScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** The chain check for an RPC address that the owner enters in settings. Plain HTTP is accepted only in a debug build. */
    val evmRpc: EvmRpc by lazy { EvmRpc(allowLocalHttp = BuildConfig.DEBUG) }

    private var evmReads: Pair<VaultSession, EvmReads>? = null

    /** The EVM reads of [session]. They are built once per unlocked session, so the scheduler keeps its health and rates across refreshes. */
    @Synchronized
    fun evmReadsFor(session: VaultSession): EvmReads {
        evmReads?.takeIf { it.first === session }?.let { return it.second }
        return EvmReads.open(session, rpcHttp, rpcScope, allowLocalHttp = BuildConfig.DEBUG).also { evmReads = session to it }
    }

    private var nativeScheduler: Pair<VaultSession, Scheduler>? = null

    /**
     * The scheduler of the Algorand and Solana balance reads of [session]. It is built once per unlocked session, so it keeps its health and rates across
     * refreshes, as the EVM reads do.
     */
    @Synchronized
    fun nativeSchedulerFor(session: VaultSession): Scheduler {
        nativeScheduler?.takeIf { it.first === session }?.let { return it.second }
        return NativeBalances.schedulerOf(session, rpcHttp, rpcScope, allowLocalHttp = BuildConfig.DEBUG).also { nativeScheduler = session to it }
    }

    /** The tokens each EVM chain reads for [session]: the bundled defaults, then the owner's own. The owner's list is read once, here. */
    fun tokensFor(session: VaultSession): (EvmChain) -> List<Token> {
        val defaults = defaultTokens
        val custom = CustomTokenStore.read(session)
        return { chain -> TokenCatalog.onChain(defaults, custom, chain.chainId) }
    }

    /** The one HTTP client of Bitcoin data. Every Esplora provider of this process shares its connection pool. */
    val http: Http by lazy {
        Http(
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build(),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The Esplora providers, then Blockchair as the last fallback: a FALLBACK_ONLY provider serves a Bitcoin read only when no other provider can
     * serve it now. Blockchair serves UTXO reads only, so address statistics still need an Esplora provider. Each owner's server is a provider of
     * its own network, so its address never reaches another network.
     */
    private val providers: List<Provider> by lazy {
        listOf(
            MempoolProvider(),
            BlockstreamProvider(),
            BlockchairProvider(fallbackOnly = true),
            CustomEsploraProvider("mainnet"),
            CustomEsploraProvider("testnet4"),
        )
    }

    /** The one scheduler of Bitcoin data calls. It reads the open vault's cache and provider settings, and has neither while the vault is locked. */
    val scheduler: Scheduler by lazy {
        Scheduler(
            providers = providers,
            configs = { lumioVault()?.configs().orEmpty() },
            http = http,
            clock = MonotonicClock,
            random = Random.Default,
            scope = scope,
            cache = { lumioVault()?.cache },
        )
    }

    /**
     * The one scheduler of price calls. CoinGecko is asked first only while it has a key and is enabled, and DefiLlama answers otherwise or when
     * CoinGecko fails. It keeps its own rates and cooldowns, apart from the Bitcoin scheduler, and it reads the same cache and provider settings.
     */
    internal val priceScheduler: Scheduler by lazy {
        Scheduler(
            providers = ProviderCatalog.priceProviders(),
            configs = { ProviderCatalog.withDefaults(lumioVault()?.configs().orEmpty()) },
            http = http,
            clock = MonotonicClock,
            random = Random.Default,
            scope = scope,
            cache = { lumioVault()?.cache },
        )
    }

    /** The USD prices of the native coins and their 24-hour change. Each screen that shows a price reads them from here. */
    val prices: PriceRepository by lazy { PriceRepository(priceScheduler) { lumioVault()?.cache } }

    /** The one queue of Bitcoin scan jobs. It reads the open vault's rows, and nothing while the vault is locked. */
    val scanQueue: ScanQueue by lazy { ScanQueue { lumioVault()?.scanRows } }

    /**
     * The one runner of scan jobs, shared by every screen, so a scan goes on when its screen closes. Four accounts scan at once: each account's two
     * chains are walked one request at a time, so a wallet of four accounts is limited by the servers' rate, not by one walk after another.
     */
    val scanRunner: ScanRunner by lazy { ScanRunner(scanQueue, parallelism = 4) { job, step -> scanJobOf(job, step) } }

    /** The address statistics this session has read. Every scan shares them, and the transaction feed's Bitcoin reads too. */
    internal val addressStats = AddressStatsCache()

    /**
     * True when the registry or the bundled default list names [contract] on [network]. Lumio knows such a token, so the spam heuristic and Home judge it
     * as they judge any coin. Any other token is unknown: the owner added it, or someone else sent it.
     */
    fun isKnownToken(network: NetworkId, contract: String): Boolean {
        val chainId = network.chain.toLongOrNull() ?: return false
        return registry.token(chainId, contract) != null ||
            defaultTokens.any { it.chainId == chainId && it.contract.equals(contract, ignoreCase = true) }
    }

    /**
     * The transaction history of [vault]: the scheduler's provider reads, written to the vault's store. Its EVM reads follow the owner's [PreferRpcStore]
     * choice, and its native coins are the chains that the vault lists, the registry's and the owner's own.
     */
    fun txHistory(vault: LumioVault): TxHistory {
        val session = vault.session
        val chains = EvmCatalog.chains(registry, EvmSettingsStore.read(session))
        val owned = CustomTokenStore.read(session)
        return TxHistory(
            scheduler = scheduler,
            store = vault.txs,
            spam = TxSpam(
                isKnownToken = { network, contract -> isKnownToken(network, contract) },
                isCustomToken = { network, contract -> CustomTokenStore.holds(owned, network, contract) },
            ),
            nativeAsset = { net ->
                chains.firstOrNull { net.family == Family.EVM && it.chainId.toString() == net.chain }
                    ?.let { TxAsset(contract = null, symbol = it.symbol, name = it.name, decimals = it.decimals) }
            },
            preferRpc = { PreferRpcStore.read(session) },
            clock = WallClock,
        )
    }

    private var database: LumioDatabase? = null

    /** The open vault's Lumio state, or null while the vault is locked, or for a decoy vault, which keeps none. */
    fun lumioVault(): LumioVault? = vaultHolder.sessionAttachment() as? LumioVault

    /**
     * The Bitcoin network of the side the owner chose, read from [session]. Only its scan jobs run, so testnet4 and mainnet never scan together.
     * Until a choice is stored, it is mainnet.
     */
    fun selectedBtcNetwork(session: VaultSession): BtcNetwork = NetworkModeStore.read(session).btcNetwork()

    /** The network id of [selectedBtcNetwork] for [session]. */
    fun selectedNetwork(session: VaultSession): NetworkId = selectedBtcNetwork(session).networkId()

    /**
     * Runs a pass over the pending scan jobs of the selected network, on the application's own scope, so the pass goes on when the screen that
     * started it closes. The returned job completes when the pass has finished.
     */
    fun startScan(net: NetworkId): Job = scope.launch {
        val started = System.nanoTime()
        val jobs = scanRunner.pass(net)
        // Debug builds only: one line per pass, read beside the request lines of the same tag.
        if (BuildConfig.DEBUG) Log.d(EsploraProtocol.LOG_TAG, "pass chain=${net.chain} jobs=$jobs ms=${elapsedMs(started)}")
    }

    /** Runs [block] on the application's own scope, so it goes on when the screen that started it closes. */
    fun runInAppScope(block: suspend () -> Unit): Job = scope.launch { block() }

    override fun onCreate() {
        super.onCreate()
        // The QR decoder runs in an isolated process that cannot open preferences or files; it needs none of the app's state.
        if (android.os.Process.isIsolated()) return
        logBackupExclusion()
    }

    /** Opens Lumio's state for an unlocked vault, and resumes the scan jobs that an earlier session left pending. A decoy vault keeps none. */
    private fun openVault(session: VaultSession): SessionAttachment? {
        if (session.isDecoy) return null
        val vault = LumioVault(session, database())
        scope.launch {
            vault.cache.purgeUnreadable()
            scanQueue.purgeUnreadable()
            vault.store.retain(bitcoinRefs(session).map { it.key }.toSet())
            scanRunner.pass(selectedNetwork(session))
        }
        return vault
    }

    /** The scan of one job, with the open vault's accounts and last known balances. */
    private suspend fun scanJobOf(job: QueuedJob, step: suspend (Int) -> Unit) {
        val vault = lumioVault() ?: throw ProviderError.Fatal("Unlock Lumio to scan.")
        val started = System.nanoTime()
        scanJob(
            job = job,
            refs = bitcoinRefs(vault.session),
            scheduler = scheduler,
            store = vault.store,
            memo = addressStats,
            nowMs = { System.currentTimeMillis() },
            step = step,
        )
        if (BuildConfig.DEBUG) Log.d(EsploraProtocol.LOG_TAG, "account done priority=${job.job.priority} ms=${elapsedMs(started)}")
    }

    private fun elapsedMs(since: Long): Long = (System.nanoTime() - since) / 1_000_000

    /** The database of the open vault. It is reopened when the vault's file has gone, as a wipe removes it. */
    @Synchronized
    private fun database(): LumioDatabase {
        val file = File(File(noBackupFilesDir, "vault"), LumioDatabase.FILE_NAME)
        database?.takeIf { it.isOpen && file.isFile }?.let { return it }
        database?.close()
        return LumioDatabase.open(this, file).also { database = it }
    }
}
