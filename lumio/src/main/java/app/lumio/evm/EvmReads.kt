package app.lumio.evm

import app.lensly.core.vault.VaultSession
import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.PreferRpcStore
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.ContractCall
import app.lumio.data.provider.DataPlugins
import app.lumio.data.provider.EstimateGas
import app.lumio.data.provider.GasFees
import app.lumio.data.provider.GasQuote
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Nonce
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.rpc.RpcPlugins
import app.lumio.data.sched.Priority
import app.lumio.data.sched.ProviderHealth
import app.lumio.data.sched.Scheduler
import java.math.BigInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A read-only call of a contract on a chain, as the chain answers it. [to] is the contract and [data] the encoded call; the answer is the return data in hex. */
interface ContractReads {
    suspend fun call(chain: EvmChain, to: String, data: String): String
}

/**
 * The EVM chain reads and broadcasts, routed by the scheduler. A chain whose RPC address the owner set reads only from that address, so the
 * owner's addresses never reach a public provider. Every other chain reads from the public pool: its DATA providers first, then its RPC providers.
 * A failure reaches the caller as [RpcFailure], and a call that runs past [deadlineMs] fails as unreachable.
 */
class EvmReads(
    private val pool: Scheduler,
    private val owner: Scheduler,
    private val settings: () -> EvmSettings,
    private val deadlineMs: Long = DEADLINE_MS,
    /** The pool that balance reads use while [preferRpc] holds. It has no explorers. Without it, [pool] serves every read. */
    private val rpcOnly: Scheduler = pool,
    private val preferRpc: () -> Boolean = { false },
) : ContractReads {
    suspend fun balance(chain: EvmChain, address: String, priority: Priority = Priority.VISIBLE): BigInteger =
        run(chain, priority, NativeBalance(net(chain), address), if (preferRpc()) rpcOnly else pool)

    suspend fun nonce(chain: EvmChain, address: String, pending: Boolean, priority: Priority = Priority.VISIBLE): Long =
        run(chain, priority, Nonce(net(chain), address, pending))

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

    /** What the pool, or else the owner's scheduler, knows about [providerId] now, or null when neither holds that provider. */
    suspend fun statusOf(providerId: String): ProviderHealth? = pool.statusOf(providerId) ?: owner.statusOf(providerId)

    private suspend fun <T> run(chain: EvmChain, priority: Priority, req: Request<T>, publicPool: Scheduler = pool): T {
        val scheduler = if (settings().rpc.containsKey(chain.chainId)) owner else publicPool
        return try {
            withTimeout(deadlineMs) { scheduler.run(req, priority) }
        } catch (e: ProviderError) {
            throw e.toRpcFailure()
        } catch (e: TimeoutCancellationException) {
            throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoints did not answer in time.")
        }
    }

    companion object {
        /** The longest one read or broadcast may take, across its retries and failovers. */
        const val DEADLINE_MS = 30_000L

        /**
         * The reads of the open vault [session]. The pool and the owner's addresses are two schedulers over one HTTP stack, so each keeps its
         * own health and rates. The owner's addresses come from the vault's EVM settings, read on each call, so a change takes effect at once.
         */
        fun open(session: VaultSession, http: Http, scope: CoroutineScope, allowLocalHttp: Boolean): EvmReads {
            val settings = { EvmSettingsStore.read(session) }
            val configs = {
                val saved = ProviderConfigStore(session).load()
                val owner = settings().rpc.mapKeys { it.key.toString() }
                saved + (CustomRpc.ID to (saved[CustomRpc.ID] ?: ProviderConfig()).copy(chainUrls = owner))
            }
            return EvmReads(
                pool = Scheduler(DataPlugins.evm() + RpcPlugins.pool(), configs, http, MonotonicClock, Random.Default, scope),
                owner = Scheduler(listOf(CustomRpc(allowLocalHttp)), configs, http, MonotonicClock, Random.Default, scope),
                settings = settings,
                rpcOnly = Scheduler(
                    DataPlugins.evm(preferRpc = true) + RpcPlugins.pool(), configs, http, MonotonicClock, Random.Default, scope,
                ),
                preferRpc = { PreferRpcStore.read(session) },
            )
        }

        private fun net(chain: EvmChain) = NetworkId(Family.EVM, chain.chainId.toString(), chain.testnet)
    }
}
