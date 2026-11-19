package app.lumio.data.provider.solana

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.rpc.PUBLIC_LIMITS
import app.lumio.data.rpc.RpcUrl
import kotlinx.coroutines.delay

/** The Solana devnet cluster, as the chain name "devnet" of the Solana family. */
val SOLANA_DEVNET = NetworkId(Networks.SOL_DEVNET, testnet = true)

/** The Solana mainnet-beta cluster, as the chain name "mainnet-beta" of the Solana family. */
val SOLANA_MAINNET = NetworkId(Networks.SOL_MAINNET, testnet = false)

/**
 * The operations a Solana RPC provider serves: balances, blockhashes, block heights, history, token balances, broadcasts, signature statuses,
 * account reads and the rent-exempt minimum.
 */
private val SOLANA_OPS = setOf(
    Op.NATIVE_BALANCE, Op.BLOCKHASH, Op.BLOCK_HEIGHT, Op.TX_HISTORY, Op.TOKEN_BALANCES, Op.BROADCAST, Op.TX, Op.ACCOUNT_INFO, Op.RENT_EXEMPT,
)

/**
 * A Solana JSON-RPC provider. [endpoint] gives the address that serves a network under a config, or null when there is none, and then the provider does
 * not serve that network. The calls and the 429 back-off are the same for every endpoint ([SolanaCalls]). [pause] is how a call waits before it sends a
 * request again after a 429. Tests replace it, so they run in no real time.
 */
abstract class SolanaRpcProvider(
    override val id: String,
    override val traits: Set<Trait>,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Provider {
    override val kind: Kind = Kind.RPC

    /** Whether calls may use plain HTTP to a local host. Only the owner's own address in a debug build says yes. */
    protected open val allowLocalHttp: Boolean get() = false

    /** The address that serves [net] under [cfg], or null when this provider does not serve it. */
    abstract fun endpoint(net: NetworkId, cfg: ProviderConfig): String?

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        net.isSol && op in SOLANA_OPS && endpoint(net, cfg) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = PUBLIC_LIMITS

    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        val url = endpoint(req.net, cfg) ?: throw ProviderError.Unsupported()
        return SolanaCalls(http, url, allowLocalHttp, pause).execute(req)
    }
}

/**
 * The public Solana endpoints, keyless: `https://api.devnet.solana.com` and `https://api.mainnet-beta.solana.com`. They take no key, so the provider
 * never sends one, and they use the public-pool rates.
 */
class SolanaRpc : SolanaRpcProvider("solana", setOf(Trait.KEYLESS)) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? = if (net.isSol) ENDPOINTS[net.name] else null

    companion object {
        /** The public endpoint of each Solana cluster, by chain name. */
        val ENDPOINTS: Map<String, String> = mapOf(
            "devnet" to "https://api.devnet.solana.com",
            "mainnet-beta" to "https://api.mainnet-beta.solana.com",
        )
    }
}

/**
 * The owner's own Solana RPC for [chain], from the `baseUrl` of its settings. It follows [app.lumio.data.esplora.CustomEsploraProvider]: one provider per
 * chain, serving nothing until an address is set. An address that [RpcUrl] refuses (plain HTTP outside a debug build, a user name, another scheme)
 * serves nothing either. While it is enabled it is the only provider of its chain ([Trait.OWN_SERVER]), so the owner's addresses never reach a public
 * endpoint.
 *
 * The EVM custom RPC ([app.lumio.data.rpc.CustomRpc]) keeps its addresses per chain in `chainUrls`. The EVM settings replace that map whenever they are
 * read, so a Solana address stored there would be lost. The `baseUrl` of the same config is not written by them.
 */
class CustomSolanaRpc(
    private val chain: String,
    override val allowLocalHttp: Boolean,
    pause: suspend (Long) -> Unit = { delay(it) },
) : SolanaRpcProvider(idFor(chain), setOf(Trait.OWN_SERVER), pause) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? {
        if (!net.isSol || net.name != chain) return null
        val raw = cfg.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return try {
            RpcUrl.validate(raw, allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** The prefix of every owner's Solana RPC id. */
        const val ID_PREFIX = "custom-sol-"

        /** The provider id of the owner's Solana RPC for [chain]. */
        fun idFor(chain: String): String = ID_PREFIX + chain
    }
}
