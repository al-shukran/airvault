package app.lumio.evm

import app.lensly.core.vault.VaultSession
import app.lumio.data.MonotonicClock
import app.lumio.data.config.PreferRpcStore
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.ContractCall
import app.lumio.data.provider.DataPlugins
import app.lumio.data.provider.EstimateGas
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.GasFees
import app.lumio.data.provider.GasQuote
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Nonce
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TxReceipt
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.rpc.RpcPlugins
import app.lumio.data.sched.Priority
import app.lumio.data.sched.ProviderHealth
import app.lumio.data.sched.Scheduler
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope

/** A read-only call of a contract on a chain, as the chain answers it. [to] is the contract and [data] the encoded call; the answer is the return data in hex. */
interface ContractReads {
    suspend fun call(chain: EvmChain, to: String, data: String): String
}

/**
 * The EVM chain reads and broadcasts, routed by the scheduler. A chain whose RPC address the owner set reads only from that address, so the
 * owner's addresses never reach a public provider. Every other chain reads from the public pool: its DATA providers first, then its RPC providers.
 * A failure reaches the caller as [RpcFailure]. The scheduler sets the deadlines: a read waits in its queue as long as it takes, and each request's
 * clock starts when it is sent.
 */
class EvmReads(
    private val pool: Scheduler,
    /** Makes the scheduler for one chain's custom address. Each chain gets its own, so a failing address cannot trip the circuit of another chain's. */
    private val newOwner: () -> Scheduler,
    private val settings: () -> EvmSettings,
    /** The pool that balance reads use while [preferRpc] holds. It has no explorers. Without it, [pool] serves every read. */
    private val rpcOnly: Scheduler = pool,
    private val preferRpc: () -> Boolean = { false },
) : ContractReads {
    /** Per chain, the address in use and the scheduler made for it, replaced when the owner changes the address, so a new address starts with no failures. */
    private val owners = ConcurrentHashMap<Long, Pair<String?, Scheduler>>()

    private fun ownerOf(chainId: Long): Scheduler {
        val url = settings().activeRpc(chainId)
        return owners.compute(chainId) { _, held -> if (held?.first == url) held else url to newOwner() }!!.second
    }

    suspend fun balance(chain: EvmChain, address: String, priority: Priority = Priority.VISIBLE): BigInteger =
        run(chain, priority, NativeBalance(net(chain), address), if (preferRpc()) rpcOnly else pool)

    suspend fun nonce(chain: EvmChain, address: String, pending: Boolean, priority: Priority = Priority.VISIBLE): Long =
        run(chain, priority, Nonce(net(chain), address, pending))

    /** The receipt of [hash] on [chain], or null while the network has not included it. */
    suspend fun receipt(chain: EvmChain, hash: String, priority: Priority = Priority.BACKGROUND): EvmReceipt? =
        run(chain, priority, TxReceipt(net(chain), hash))

    suspend fun fees(chain: EvmChain, priority: Priority = Priority.VISIBLE): GasQuote = run(chain, priority, GasFees(net(chain)))

    suspend fun estimateGas(
        chain: EvmChain,
        from: String,
        to: String,
        valueWei: BigInteger,
        data: String = "0x",
        priority: Priority = Priority.VISIBLE,
    ): BigInteger = run(chain, priority, EstimateGas(net(chain), from, to, valueWei, data))

    suspend fun broadcast(chain: EvmChain, raw: ByteArray): String = run(chain, Priority.FOREGROUND, Broadcast(net(chain), raw))

    /** The return data of a read-only call of [to] with [data] on [chain], in hex. Token balances and metadata are read this way. */
    override suspend fun call(chain: EvmChain, to: String, data: String): String =
        run(chain, Priority.VISIBLE, ContractCall(net(chain), to, data))

    /** What the pool knows about [providerId] now, or null when it does not hold that provider. */
    suspend fun statusOf(providerId: String): ProviderHealth? = pool.statusOf(providerId)

    /** What the scheduler of the owner's address on [chainId] knows about it now. A chain with no reads yet is ready. */
    suspend fun customStatusOf(chainId: Long): ProviderHealth? = ownerOf(chainId).statusOf(CustomRpc.ID)

    private suspend fun <T> run(chain: EvmChain, priority: Priority, req: Request<T>, publicPool: Scheduler = pool): T {
        val scheduler = if (settings().activeRpc(chain.chainId) != null) ownerOf(chain.chainId) else publicPool
        return try {
            scheduler.run(req, priority)
        } catch (e: ProviderError) {
            throw e.toRpcFailure()
        }
    }

    companion object {
        /**
         * The reads of the open vault [session]. The pool and each chain's owner address are separate schedulers over one HTTP stack, so each keeps its
         * own health and rates. The owner's addresses come from the vault's EVM settings, read on each call, so a change takes effect at once.
         */
        fun open(session: VaultSession, http: Http, scope: CoroutineScope, allowLocalHttp: Boolean): EvmReads {
            val settings = { EvmSettingsStore.read(session) }
            val configs = {
                val saved = ProviderConfigStore(session).load()
                val owner = settings().activeRpcUrls()
                saved + (CustomRpc.ID to (saved[CustomRpc.ID] ?: ProviderConfig()).copy(chainUrls = owner))
            }
            return EvmReads(
                pool = Scheduler(DataPlugins.evm() + RpcPlugins.pool(), configs, http, MonotonicClock, Random.Default, scope),
                newOwner = { Scheduler(listOf(CustomRpc(allowLocalHttp)), configs, http, MonotonicClock, Random.Default, scope) },
                settings = settings,
                rpcOnly = Scheduler(
                    DataPlugins.evm(preferRpc = true) + RpcPlugins.pool(), configs, http, MonotonicClock, Random.Default, scope,
                ),
                preferRpc = { PreferRpcStore.read(session) },
            )
        }

        private fun net(chain: EvmChain) = NetworkId(Networks.evm(chain.chainId), chain.testnet)
    }
}
