package app.lumio.data.provider

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import okhttp3.OkHttpClient

/** A data provider answers chain-data reads, and an RPC provider answers JSON-RPC calls. Balance reads try data providers first. */
enum class Kind { DATA, RPC }

/** What a provider promises about itself. Routing reads these traits, and no call changes them. */
enum class Trait { KEYLESS, KEY_OPTIONAL, KEY_REQUIRED, MEV_PROTECTED, PRIVACY, ESPLORA_COMPAT }

/** The operations a request can name. A provider declares, per network, which of them it serves. */
enum class Op {
    ADDRESS_STATS, ADDRESS_TXS, ADDRESS_UTXOS, TIP_HEIGHT, FEE_ESTIMATES, BLOCK, TX,
    NATIVE_BALANCE, NONCE, TOKEN_BALANCES, TX_HISTORY, BROADCAST, RPC_CALL,
}

/** The one HTTP stack for providers: the OkHttp client that the app already ships. */
class Http(val client: OkHttpClient)

/**
 * A chain-data or RPC source. A provider maps each typed [Request] to its own API and throws [ProviderError] for each failure. The scheduler
 * treats an [java.io.IOException] from [execute] as [ProviderError.Transient]. Cooldowns, circuits and rates are never kept here: the
 * scheduler holds them.
 */
interface Provider {
    val id: String
    val kind: Kind
    val traits: Set<Trait>

    /** Whether this provider can serve [op] on [net] with [cfg]. A key-required op answers false when there is no key. */
    fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean

    /** The rate and concurrency this provider allows with [cfg], before any override in the config. */
    fun defaultLimits(cfg: ProviderConfig): Limits

    /** Runs [req] and returns its result, or throws [ProviderError]. */
    suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T
}
