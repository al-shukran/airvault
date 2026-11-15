package app.lumio.tokens

import app.lensly.core.vault.VaultSession
import app.lumio.evm.ContractReads
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.evm.Portfolio
import app.lumio.evm.RpcFailure
import app.lumio.pair.PairedWallet
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One token balance that Lumio read for an address on a chain: [amount] in the token's base units, as a decimal string, and [atMs], the time of
 * the read, in epoch milliseconds. The address is one of the paired wallet [walletId]. A read is identified by its [key].
 */
@Serializable
data class CachedTokenBalance(
    val walletId: String,
    val chainId: Long,
    val address: String,
    val contract: String,
    val amount: String,
    val atMs: Long,
) {
    /** The identity of a read: one wallet's address, on one chain, for one contract. A later read with the same identity replaces this one. */
    val key: String
        get() = keyOf(walletId, chainId, address, contract)

    companion object {
        fun keyOf(walletId: String, chainId: Long, address: String, contract: String): String = "$walletId|$chainId|$address|$contract"
    }
}

/** The last token balances of every paired wallet, kept as an encrypted vault record. It is read like the native balance cache: shown with its time. */
object TokenBalanceCache {
    /** The vault record that holds the reads. */
    const val RECORD = "lumio-token-balances"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class CacheFile(val entries: List<CachedTokenBalance> = emptyList())

    /** The cached reads, or none when nothing is stored or the record does not parse. */
    fun read(session: VaultSession): List<CachedTokenBalance> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyList()
        return try {
            json.decodeFromString(CacheFile.serializer(), bytes.decodeToString()).entries
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    fun write(session: VaultSession, entries: List<CachedTokenBalance>) {
        session.putVaultRecord(RECORD, json.encodeToString(CacheFile.serializer(), CacheFile(entries)).encodeToByteArray())
    }
}

/** A token balance that a wallet's shown address holds on a chain, as the cache has it, with the time of the read. */
data class TokenHolding(val chain: EvmChain, val token: Token, val amount: BigInteger, val atMs: Long)

/**
 * Reads the token balances of one wallet's shown addresses and stores them in the cache, chain by chain. Each chain is written as soon as it is read,
 * so the balances that have arrived are kept even when another chain fails. A chain that fails keeps the reads it had. A token that does not answer
 * keeps its earlier read too. Chains run side by side, and the addresses of one chain are read in order.
 */
object TokenPortfolio {
    /** One refresh at a time, as the native refresh has. */
    private val lock = Mutex()

    /** Read-modify-write of the cache is one step at a time, so two chains never lose each other's reads. */
    private val writes = Mutex()

    /**
     * Reads the tokens that [tokensOf] lists on each of [chains] for [wallet], through [reads], and stores the reads in the cache of [session]. A wallet
     * without an EVM account key is skipped. Endpoint failures are not thrown: they leave the cache as it was for that chain.
     */
    suspend fun refresh(
        session: VaultSession,
        wallet: PairedWallet,
        settings: EvmSettings,
        chains: List<EvmChain>,
        tokensOf: (EvmChain) -> List<Token>,
        reads: ContractReads,
        nowMs: () -> Long = System::currentTimeMillis,
    ) {
        val account = wallet.evm ?: return
        lock.withLock {
            val addresses = Portfolio.addresses(account, settings.addressCount)
            coroutineScope {
                chains.map { chain ->
                    async { readChain(session, wallet.id, addresses, chain, tokensOf(chain), reads, nowMs) }
                }.awaitAll()
            }
        }
    }

    private suspend fun readChain(
        session: VaultSession,
        walletId: String,
        addresses: List<String>,
        chain: EvmChain,
        tokens: List<Token>,
        reads: ContractReads,
        nowMs: () -> Long,
    ) {
        if (tokens.isEmpty()) return
        val fresh = ArrayList<CachedTokenBalance>()
        try {
            for (address in addresses) {
                val balances = TokenBalances.read(reads, chain, address, tokens.map { it.contract })
                val atMs = nowMs()
                for ((contract, amount) in balances) {
                    if (amount != null) fresh += CachedTokenBalance(walletId, chain.chainId, address, contract, amount.toString(), atMs)
                }
            }
        } catch (e: RpcFailure) {
            // The reads made before the failure are kept, and the rest keep their earlier reads.
        }
        writes.withLock {
            withContext(Dispatchers.IO) {
                val merged = merged(TokenBalanceCache.read(session), walletId, chain.chainId, addresses.toSet(), fresh)
                TokenBalanceCache.write(session, merged)
            }
        }
    }

    /**
     * [cache] with [fresh] replacing the reads it holds for the same wallet, chain, address and contract. The reads of [walletId] on [chainId] for an
     * address that is not in [shown] are dropped. Other reads are kept as they are.
     */
    internal fun merged(
        cache: List<CachedTokenBalance>,
        walletId: String,
        chainId: Long,
        shown: Set<String>,
        fresh: List<CachedTokenBalance>,
    ): List<CachedTokenBalance> {
        val replaced = fresh.map { it.key }.toSet()
        val kept = cache.filter { it.walletId != walletId || it.chainId != chainId || (it.address in shown && it.key !in replaced) }
        return kept + fresh
    }

    /** The cached token balances of [wallet]'s shown addresses on [chains], for the tokens [tokensOf] lists. A balance with no read has no entry. */
    fun holdings(
        wallet: PairedWallet,
        count: Int,
        chains: List<EvmChain>,
        tokensOf: (EvmChain) -> List<Token>,
        cache: List<CachedTokenBalance>,
    ): List<TokenHolding> {
        val account = wallet.evm ?: return emptyList()
        val byKey = cache.associateBy { it.key }
        return Portfolio.addresses(account, count).flatMap { address ->
            chains.flatMap { chain ->
                tokensOf(chain).mapNotNull { token ->
                    byKey[CachedTokenBalance.keyOf(wallet.id, chain.chainId, address, token.contract)]
                        ?.let { TokenHolding(chain, token, BigInteger(it.amount), it.atMs) }
                }
            }
        }
    }
}
