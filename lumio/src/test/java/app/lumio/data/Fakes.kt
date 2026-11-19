package app.lumio.data

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TipHeight
import app.lumio.data.provider.Trait
import app.lumio.data.provider.explorer.ExplorerTx
import java.math.BigInteger
import kotlinx.coroutines.delay

/** Ethereum mainnet, as the EVM family names it. */
val ETH = NetworkId(Networks.evm(1), testnet = false)

/** Bitcoin mainnet. */
val BTC = NetworkId(Networks.BTC_MAINNET, testnet = false)

/** A clock that the test moves by hand. */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

/** True when [needle] occurs in these bytes. Tests use it to check that a secret is absent from a stored file. */
fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

/**
 * A provider that answers from a script. [outcomes] are used in order: a [ProviderError] is thrown, and anything else is the result. When the
 * script runs out, the provider answers with a fixed value for each request type. Each call records the time it started, and the peak number of
 * calls in flight.
 */
class FakeProvider(
    override val id: String,
    override val kind: Kind = Kind.DATA,
    override val traits: Set<Trait> = setOf(Trait.KEYLESS),
    private val networks: Set<NetworkId> = setOf(ETH),
    private val ops: Set<OpKey> = setOf(Ops.BTC_TIP_HEIGHT, Ops.EVM_NATIVE_BALANCE, Ops.EVM_BROADCAST),
    private val limits: Limits = Limits(rps = 1_000.0, burst = 1_000, maxConcurrent = 100),
    private val latencyMs: Long = 0L,
    private val now: () -> Long = { 0L },
) : Provider {
    val outcomes = ArrayDeque<Any>()
    val callTimes = ArrayList<Long>()
    val calls: Int get() = callTimes.size
    var maxInFlight = 0
        private set
    private var inFlight = 0

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean =
        net in networks && op in ops && (Trait.KEY_REQUIRED !in traits || !cfg.apiKey.isNullOrBlank())

    override fun defaultLimits(cfg: ProviderConfig): Limits = limits

    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        callTimes.add(now())
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            if (latencyMs > 0) delay(latencyMs)
            val next = if (outcomes.isEmpty()) defaultAnswer(req) else outcomes.removeFirst()
            if (next is ProviderError) throw next
            @Suppress("UNCHECKED_CAST")
            return next as T
        } finally {
            inFlight--
        }
    }

    private fun defaultAnswer(req: Request<*>): Any = when (req) {
        is TipHeight -> 100L
        is NativeBalance -> BigInteger.ONE
        is Broadcast -> "0xtx"
        is AddressTxHistory -> emptyList<ExplorerTx>()
        else -> error("No default answer for ${req.op}.")
    }
}
