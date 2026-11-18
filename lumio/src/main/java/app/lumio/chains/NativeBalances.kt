package app.lumio.chains

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.VaultSession
import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallet
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A native coin that Lumio reads for Algorand or Solana accounts: its [network], its [symbol] and [name] as Home shows them, and its [decimals], the
 * number of places between the base unit (microAlgos, lamports) and the whole coin.
 */
data class NativeCoin(val network: NetworkId, val symbol: String, val name: String, val decimals: Int)

/**
 * The Algorand and Solana coins that Lumio reads: the mainnet and the testnet of each family, each on the side of its network, as the EVM and Bitcoin
 * coins are. Each coin is read at the public endpoint of its network (see `AlgorandAlgod.ENDPOINTS` and `SolanaRpc.ENDPOINTS`).
 */
val NATIVE_COINS: List<NativeCoin> = listOf(
    NativeCoin(ALGORAND_MAINNET, symbol = "ALGO", name = "Algorand", decimals = 6),
    NativeCoin(SOLANA_MAINNET, symbol = "SOL", name = "Solana", decimals = 9),
    NativeCoin(ALGORAND_TESTNET, symbol = "ALGO", name = "Algorand Testnet", decimals = 6),
    NativeCoin(SOLANA_DEVNET, symbol = "SOL", name = "Solana Devnet", decimals = 9),
)

/** A read that did not answer: its [network], and the owner's words for why. The cached balance of that network stays as it was. */
data class NativeFailure(val network: NetworkId, val message: String)

/**
 * The Algorand and Solana native balances. A refresh reads the balance of each paired wallet's address on each coin of [NATIVE_COINS] through a scheduler,
 * and keeps the reads in [NativeBalanceCache]. Home lists them from that cache, as it lists the EVM balances.
 */
object NativeBalances {
    /** One refresh at a time: a second request waits, and then reads the cache the first one wrote. */
    private val lock = Mutex()

    /** The coins of [mode]'s side: the mainnet pair on the mainnet side, the testnet and devnet pair on the testnet side. */
    fun coinsOf(mode: NetworkMode): List<NativeCoin> = NATIVE_COINS.filter { mode.holds(it.network.testnet) }

    /**
     * The addresses of [wallet] on [network], in account order: the Algorand address of each Algorand key, or the base58 address of each Solana key.
     * A wallet with no account of the family of [network] has none.
     */
    fun addresses(wallet: PairedWallet, network: NetworkId): List<String> = when (network.family) {
        Family.ALGO -> wallet.algorand.map { AlgorandAccount.addressOf(it) }
        Family.SOL -> wallet.solana.map { SolanaAccount.addressOf(it) }
        else -> emptyList()
    }

    /**
     * The scheduler of the Algorand and Solana reads of [session]: the providers of the catalog, with the provider settings of the session. It keeps its
     * own health and rates, apart from the EVM and Bitcoin schedulers.
     */
    fun schedulerOf(session: VaultSession, http: Http, scope: CoroutineScope, allowLocalHttp: Boolean): Scheduler = Scheduler(
        providers = ProviderCatalog.algorandProviders() + ProviderCatalog.solanaProviders(allowLocalHttp),
        configs = { ProviderConfigStore(session).load() },
        http = http,
        clock = MonotonicClock,
        random = Random.Default,
        scope = scope,
    )

    /**
     * Reads the balance of each address of [wallets] on each of [coins] through [scheduler], and stores the reads in the cache of [session]. The addresses of
     * one wallet on one network are read in order, and the first failure ends that network for that wallet: its cached reads stay as they were, and the
     * failure is returned. Reads of addresses that are no longer paired are dropped.
     */
    suspend fun refresh(
        session: VaultSession,
        wallets: List<PairedWallet>,
        coins: List<NativeCoin>,
        scheduler: Scheduler,
        nowMs: () -> Long = System::currentTimeMillis,
    ): List<NativeFailure> = lock.withLock {
        var cache = withContext(Dispatchers.IO) { NativeBalanceCache.read(session) }
        val failures = ArrayList<NativeFailure>()
        for (wallet in wallets) for (coin in coins) {
            val shown = addresses(wallet, coin.network)
            if (shown.isEmpty()) continue
            val fresh = try {
                shown.map { address ->
                    val balance = scheduler.run(NativeBalance(coin.network, address), Priority.VISIBLE)
                    CachedNativeBalance(wallet.id, coin.network, address, balance.toString(), nowMs())
                }
            } catch (e: ProviderError) {
                failures.add(NativeFailure(coin.network, e.message ?: "The endpoint failed."))
                emptyList()
            }
            cache = merged(cache, wallet.id, coin.network, shown.toSet(), fresh)
        }
        withContext(Dispatchers.IO) { NativeBalanceCache.write(session, cache) }
        failures
    }

    /**
     * [cache] with [fresh] replacing the reads it holds for the same wallet, network and address. The reads of [walletId] on [network] for an address that
     * is not in [shown] are dropped. Other reads are kept as they are.
     */
    internal fun merged(
        cache: List<CachedNativeBalance>,
        walletId: String,
        network: NetworkId,
        shown: Set<String>,
        fresh: List<CachedNativeBalance>,
    ): List<CachedNativeBalance> {
        val replaced = fresh.map { it.key }.toSet()
        val kept = cache.filter { it.walletId != walletId || it.network != network || (it.address in shown && it.key !in replaced) }
        return kept + fresh
    }
}
