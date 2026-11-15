package app.lumio.evm

import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import app.lumio.pair.PairedWallet
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A chain that did not refresh, and the owner's words for why. Its cached reads stay as they were. */
data class ChainFailure(val chainId: Long, val message: String)

/** One address of a wallet, with its latest cached read on each enabled chain. A chain with no read has no entry. */
data class AddressRow(val index: Int, val address: String, val reads: Map<Long, CachedBalance>)

/** A native balance on [chain], as the cache holds it, with the time of its read. */
data class Holding(val chain: EvmChain, val wei: BigInteger, val atMs: Long)

/**
 * Reads the balances and nonces of one wallet's addresses on the given chains. A chain whose endpoint is missing, unreachable, malformed or
 * serves another chain is reported as a [ChainFailure] and gives no reads. Chains run side by side. The addresses of one chain are read in
 * order, and the first failure ends that chain, so an offline device waits for one timeout per chain, not one per address.
 */
class PortfolioRefresh(
    private val rpc: EvmRpc,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The reads that succeeded for a wallet, and the chains that failed. */
    class WalletReads(val reads: List<CachedBalance>, val failures: List<ChainFailure>)

    suspend fun wallet(
        walletId: String,
        account: AccountXpub,
        count: Int,
        chains: List<EvmChain>,
        rpcFor: (EvmChain) -> String?,
    ): WalletReads = coroutineScope {
        val results = chains.map { chain ->
            async(Dispatchers.IO) { readChain(walletId, account, count, chain, rpcFor(chain)) }
        }.awaitAll()
        WalletReads(results.flatMap { it.reads }, results.mapNotNull { it.failure })
    }

    private fun readChain(walletId: String, account: AccountXpub, count: Int, chain: EvmChain, url: String?): ChainResult {
        if (url == null) return ChainResult(emptyList(), ChainFailure(chain.chainId, "No RPC address is set for ${chain.name}."))
        return try {
            rpc.requireChain(url, chain.chainId)
            val reads = Portfolio.addresses(account, count).map { address ->
                CachedBalance(
                    walletId = walletId,
                    chainId = chain.chainId,
                    address = address,
                    wei = rpc.balance(url, address).toString(),
                    nonce = rpc.transactionCount(url, address),
                    atMs = nowMs(),
                )
            }
            ChainResult(reads, null)
        } catch (e: RpcFailure) {
            ChainResult(emptyList(), ChainFailure(chain.chainId, e.message ?: "The endpoint failed."))
        }
    }

    private class ChainResult(val reads: List<CachedBalance>, val failure: ChainFailure?)
}

/** The addresses Lumio shows for each wallet, and the refresh that fills the balance cache. */
object Portfolio {
    /** One refresh at a time: a second request waits, and then reads the cache the first one wrote. */
    private val lock = Mutex()

    /** The first [count] addresses of [account], at m/44'/60'/0'/0/0 and onward. */
    fun addresses(account: AccountXpub, count: Int): List<String> = (0 until count).map { AccountAddresses.addressAt(account, it) }

    /** The rows of [walletId]'s shown addresses, each with its cached read on [chains]. */
    fun rows(walletId: String, account: AccountXpub, count: Int, chains: List<EvmChain>, cache: List<CachedBalance>): List<AddressRow> {
        val byKey = cache.associateBy { it.key }
        return addresses(account, count).mapIndexed { index, address ->
            val reads = chains.mapNotNull { chain -> byKey[Triple(walletId, chain.chainId, address)]?.let { chain.chainId to it } }
            AddressRow(index, address, reads.toMap())
        }
    }

    /** The cached balances that the shown addresses of [wallets] hold on [chains], as holdings. */
    fun holdings(wallets: List<PairedWallet>, count: Int, chains: List<EvmChain>, cache: List<CachedBalance>): List<Holding> {
        val byKey = cache.associateBy { it.key }
        return wallets.flatMap { wallet ->
            val account = wallet.evm ?: return@flatMap emptyList()
            addresses(account, count).flatMap { address ->
                chains.mapNotNull { chain ->
                    byKey[Triple(wallet.id, chain.chainId, address)]?.let { Holding(chain, BigInteger(it.wei), it.atMs) }
                }
            }
        }
    }

    /**
     * Reads [wallets] on [chains] and stores the reads in the cache of [session]. The cache is read and written once for all wallets. Wallets
     * without an EVM account key are skipped. Reads of addresses that are no longer shown are dropped. Returns each wallet's chain failures.
     */
    suspend fun refresh(
        session: VaultSession,
        wallets: List<PairedWallet>,
        settings: EvmSettings,
        chains: List<EvmChain>,
        rpc: EvmRpc,
        nowMs: () -> Long = System::currentTimeMillis,
    ): Map<String, List<ChainFailure>> = lock.withLock {
        val refresher = PortfolioRefresh(rpc, nowMs)
        var cache = withContext(Dispatchers.IO) { BalanceCache.read(session) }
        val failures = LinkedHashMap<String, List<ChainFailure>>()
        for (wallet in wallets) {
            val account = wallet.evm ?: continue
            val reads = refresher.wallet(wallet.id, account, settings.addressCount, chains) { EvmCatalog.rpcFor(it, settings) }
            cache = merged(cache, wallet.id, addresses(account, settings.addressCount).toSet(), reads.reads)
            failures[wallet.id] = reads.failures
        }
        withContext(Dispatchers.IO) { BalanceCache.write(session, cache) }
        failures
    }

    /**
     * [cache] with [fresh] replacing the reads it holds for the same wallet, address and chain. The reads of [walletId] for an address that is
     * not in [shown] are dropped, so a smaller address count does not keep stale rows. Other reads are kept as they are.
     */
    internal fun merged(
        cache: List<CachedBalance>,
        walletId: String,
        shown: Set<String>,
        fresh: List<CachedBalance>,
    ): List<CachedBalance> {
        val replaced = fresh.map { it.key }.toSet()
        val kept = cache.filter { it.walletId != walletId || (it.address in shown && it.key !in replaced) }
        return kept + fresh
    }
}
