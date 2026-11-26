package app.lumio.data.rpc

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The operations an EVM RPC provider serves: balances, nonces, fees, gas estimates, broadcasts and receipts. */
private val EVM_OPS = setOf(Ops.EVM_NATIVE_BALANCE, Ops.EVM_NONCE, Ops.EVM_CALL, Ops.EVM_GAS_FEES, Ops.EVM_BROADCAST, Ops.EVM_RECEIPT)

/** A key as it goes into a URL path. Only letters, digits, '-' and '_' pass, so a key can never change the host or the path. */
private val KEY = Regex("[A-Za-z0-9_-]{1,128}")

/** Free public endpoints publish no request rate, so these are modest starting rates that the config can raise. */
internal val PUBLIC_LIMITS = Limits(rps = 4.0, burst = 8, maxConcurrent = 4)
private val KEYLESS_ALCHEMY = Limits(rps = 2.0, burst = 4, maxConcurrent = 2)
private val KEYED_ALCHEMY = Limits(rps = 15.0, burst = 30, maxConcurrent = 8)
private val TENDERLY_LIMITS = Limits(rps = 2.0, burst = 4, maxConcurrent = 2)

/**
 * An EVM JSON-RPC provider. [endpoint] gives the address that serves a network under a config. Null means the provider has none for it, and then
 * [supports] is false, so the router never offers that network to the provider. The request mapping, the shared client and the owner's failure
 * words are the same for every provider. A Solana provider adds its own [endpoint] and request mapping for the Solana networks, and nothing here changes.
 */
abstract class EvmRpcProvider(
    override val id: String,
    override val traits: Set<Trait>,
    private val limits: Limits,
) : Provider {
    override val kind: Kind = Kind.RPC

    /** Whether calls may use plain HTTP to a local host. Only the owner's own address in a debug build says yes. */
    protected open val allowLocalHttp: Boolean get() = false

    /** The address that serves [net] under [cfg], or null when this provider has none for it. */
    abstract fun endpoint(net: NetworkId, cfg: ProviderConfig): String?

    /** The addresses that serve [net] under [cfg], in the order a call tries them. One address for most providers. */
    open fun endpoints(net: NetworkId, cfg: ProviderConfig): List<String> = listOfNotNull(endpoint(net, cfg))

    /** Whether the endpoint serves [op] under [cfg]. Every EVM operation, unless a provider says otherwise. */
    open fun serves(op: OpKey, cfg: ProviderConfig): Boolean = true

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean =
        net.isEvm && op in EVM_OPS && serves(op, cfg) && endpoints(net, cfg).isNotEmpty()

    override fun defaultLimits(cfg: ProviderConfig): Limits = limits

    /**
     * Tries each address in order. A rate limit or a transient failure moves the call to the next address, and the last failure is thrown when
     * none answers. Any other failure ends the call at once, because another address would give the same answer. A [Broadcast] moves on only
     * when nothing was sent: a transient failure that may have left ends the call, so the signed bytes go to one address at most.
     */
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        val urls = endpoints(req.net, cfg).ifEmpty { throw ProviderError.Unsupported() }
        var failure: ProviderError? = null
        for (url in urls) {
            try {
                return withContext(Dispatchers.IO) { EvmCalls.execute(http.client, url, allowLocalHttp, req) }
            } catch (e: ProviderError.RateLimited) {
                failure = e
            } catch (e: ProviderError.Transient) {
                if (req is Broadcast && !e.neverSent) throw e.also { failure?.let(it::addSuppressed) }
                failure = e
            }
        }
        throw failure ?: ProviderError.Unsupported()
    }
}

/**
 * 1RPC. Keyless: `https://public.1rpc.io/<network>`. With an API key: `https://1rpc.io/<API_KEY>/<network>`, the key in the path before the
 * network. Docs: https://docs.1rpc.io/using-the-web3-api/networks and https://docs.1rpc.io/using-the-web3-api/getting-started. The docs claim
 * privacy (metadata masking, no request storage). The keyless address serves 1RPC++, the MEV-protected mode of 1RPC, so the provider carries the
 * MEV trait. The pages cited here do not name that mode; the claim rests on the provider review of 2026-11-15. Free limits: none stated for public
 * endpoints. The Starter plan allows 1,000 requests a day (https://docs.1rpc.io/using-the-web3-api/payment). Sepolia is not mapped: the free
 * plan answers `eth_getBalance` on `sepolia` with JSON-RPC error 35, "chain is not available on free plan" (probed 2026-11-15).
 */
class OneRpc : EvmRpcProvider("1rpc", setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL, Trait.PRIVACY, Trait.MEV_PROTECTED), PUBLIC_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? {
        val slug = SLUGS[net.name] ?: return null
        val key = cfg.apiKey ?: return "https://public.1rpc.io/$slug"
        return if (KEY.matches(key)) "https://1rpc.io/$key/$slug" else null
    }

    private companion object {
        /** The slugs of the EVM rows in https://docs.1rpc.io/using-the-web3-api/networks, keyed by chain ID. */
        val SLUGS = mapOf(
            "1" to "eth", "10" to "op", "25" to "cro", "40" to "telos/evm", "56" to "bnb", "66" to "oktc", "100" to "gnosis",
            "130" to "unichain", "146" to "sonic", "169" to "manta", "204" to "opbnb", "250" to "ftm", "288" to "boba/eth",
            "324" to "zksync2-era", "592" to "astr", "999" to "hyperliquid", "1101" to "polygon/zkevm", "1116" to "core",
            "1284" to "glmr", "1313161554" to "aurora", "1666600000" to "one", "4217" to "tempo", "4326" to "megaeth",
            "5000" to "mantle", "8217" to "klay", "8453" to "base", "23294" to "oasis/sapphire",
            "34443" to "mode", "42161" to "arb", "42220" to "celo", "42262" to "oasis/emerald", "43114" to "avax/c",
            "59144" to "linea", "137" to "matic", "534352" to "scroll", "560048" to "hoodi",
        )
    }
}

/**
 * PublicNode. Keyless: `https://<network>.publicnode.com`, and PublicNode offers no key option. Docs: https://publicnode.com/, which lists the
 * chains by host, tags the service "privacy first RPC endpoints", and shows an MEV-protected notice on its front page. The provider carries the
 * MEV trait for that service (MEV-Blocker). The trait covers the provider as a whole, so it is not checked chain by chain. It states no rate limit.
 */
class PublicNode : EvmRpcProvider("publicnode", setOf(Trait.KEYLESS, Trait.PRIVACY, Trait.MEV_PROTECTED), PUBLIC_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? = SLUGS[net.name]?.let { "https://$it.publicnode.com" }

    private companion object {
        /** The hosts that https://publicnode.com/ lists for these chains, keyed by chain ID. */
        val SLUGS = mapOf(
            "1" to "ethereum", "25" to "cronos", "56" to "bsc", "137" to "polygon", "1329" to "sei", "2222" to "kava",
            "8453" to "base", "80094" to "berachain", "11155111" to "ethereum-sepolia-rpc",
        )
    }
}

/**
 * Ankr. Keyed only: `https://rpc.ankr.com/<chain>/<API_KEY>`, the key as the last path segment. Docs:
 * https://www.ankr.com/docs/rpc-service/getting-started/basics-premium/ gives `https://rpc.ankr.com/eth/<key>`. The public address refuses a request
 * without a key ("Unauthorized: You must authenticate your request with an API key"), so no keyless endpoint is claimed. The docs list only `eth`.
 * They make no MEV or privacy claim, and state no free limit.
 */
class Ankr : EvmRpcProvider("ankr", setOf(Trait.KEY_REQUIRED), PUBLIC_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? {
        val slug = SLUGS[net.name] ?: return null
        val key = cfg.apiKey ?: return null
        return if (KEY.matches(key)) "https://rpc.ankr.com/$slug/$key" else null
    }

    private companion object {
        val SLUGS = mapOf("1" to "eth")
    }
}

/**
 * dRPC. Keyless: `https://<network>.drpc.org`. Docs: https://drpc.org/docs/ (its chain pages cover the networks below) and https://drpc.org/llms.txt.
 * The keyed address format and the public rate limit were not readable from any page this build could fetch, so no key option is offered. The
 * index lists "MEV Protection: Frontrunning and sandwich attack protection", but that page's text was not readable, so no MEV trait is set. Each
 * chain's endpoint answered with its chain ID when probed. BNB Smart Chain is listed in the docs but its endpoint was rate limited when probed, so
 * it is not mapped.
 */
class DRpc : EvmRpcProvider("drpc", setOf(Trait.KEYLESS), PUBLIC_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? = SLUGS[net.name]?.let { "https://$it.drpc.org" }

    private companion object {
        /** The chain pages that https://drpc.org/llms.txt indexes, and the slug each endpoint uses. */
        val SLUGS = mapOf(
            "1" to "eth", "10" to "optimism", "137" to "polygon", "250" to "fantom", "5000" to "mantle", "8453" to "base",
            "42161" to "arbitrum", "42220" to "celo", "43114" to "avalanche", "80094" to "berachain",
        )
    }
}

/**
 * Alchemy. Keyed: `https://<network>.g.alchemy.com/v2/<API_KEY>`, the key in the path (https://www.alchemy.com/docs/reference/ethereum-api-endpoints).
 * Keyless: `https://<network>.g.alchemy.com/public`, the public tier that https://www.alchemy.com/overviews/private-rpc-endpoint describes as open to
 * anyone. It answers for the four networks below, and reports the others as not enabled. MEV protection (https://www.alchemy.com/docs/reference/mev-protection)
 * is automatic on Ethereum, Arbitrum, BNB Smart Chain and Base for the standard endpoint, and that page names no keyless endpoint. The overview says it is
 * given "only to customers who send their write traffic exclusively through us". So a broadcast uses this provider only with a key. Free limits: the public
 * tier is "Low, shared across all callers". The keyed free plan allows 15 requests per second (https://www.alchemy.com/overviews/private-rpc-endpoint).
 */
class Alchemy : EvmRpcProvider("alchemy", setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL, Trait.MEV_PROTECTED), KEYLESS_ALCHEMY) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? {
        val slug = SLUGS[net.name] ?: return null
        val key = cfg.apiKey ?: return "https://$slug.g.alchemy.com/public"
        return if (KEY.matches(key)) "https://$slug.g.alchemy.com/v2/$key" else null
    }

    override fun serves(op: OpKey, cfg: ProviderConfig): Boolean = op != Ops.EVM_BROADCAST || cfg.apiKey != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (cfg.apiKey == null) KEYLESS_ALCHEMY else KEYED_ALCHEMY

    private companion object {
        val SLUGS = mapOf("1" to "eth-mainnet", "56" to "bnb-mainnet", "8453" to "base-mainnet", "42161" to "arb-mainnet")
    }
}

/**
 * Tenderly's public gateway. Keyless: `https://<network>.gateway.tenderly.co`. The Node RPC overview (https://docs.tenderly.co/node-rpc/overview.md)
 * gives that form and says every network is reachable without an access key. The docs name `mainnet` for Ethereum, and only Ethereum is mapped.
 * Other gateway slugs answered when probed, but the supported-networks page did not load, so they are not claimed. The overview makes no MEV or
 * privacy claim. Sepolia is served under the same form, as `sepolia`, and answered the balance, nonce, fee and gas reads when probed on 2026-11-15.
 * The limits are 1 GB of response data a day for each IP address, and an eth_getLogs call returns at most 3,000 results. The
 * provider is FALLBACK_ONLY: the router offers it only when no other provider can serve the request.
 */
class Tenderly : EvmRpcProvider("tenderly", setOf(Trait.KEYLESS, Trait.FALLBACK_ONLY), TENDERLY_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? = SLUGS[net.name]?.let { "https://$it.gateway.tenderly.co" }

    private companion object {
        val SLUGS = mapOf("1" to "mainnet", "11155111" to "sepolia")
    }
}

/**
 * The owner's own RPC address for each chain, from EVM settings, as [ProviderConfig.chainUrls] keyed by chain name. It serves only the chains the
 * owner set, and [app.lumio.evm.EvmReads] sends those chains here alone, so the owner's addresses never reach a public provider. It makes no MEV or
 * privacy claim, and it states no limit, so its rates are the defaults.
 */
class CustomRpc(override val allowLocalHttp: Boolean) : EvmRpcProvider(ID, emptySet(), PUBLIC_LIMITS) {
    override fun endpoint(net: NetworkId, cfg: ProviderConfig): String? {
        val raw = cfg.chainUrls[net.name] ?: return null
        return try {
            RpcUrl.validate(raw, allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** The provider id. It keys this provider's config in the vault record. */
        const val ID = "custom"
    }
}

/** The providers of the public pool. The router ranks them by its own rules, so their order here only decides [builtInEndpoint]. */
object RpcPlugins {
    fun pool(): List<Provider> = listOf(OneRpc(), PublicNode(), Ankr(), DRpc(), Alchemy(), Tenderly(), ChainlistRpc())

    /**
     * The address shown as the built-in RPC of [net]: the first address that a pool provider has for it, in the order [pool] lists them, read with no
     * key set, so no key can reach the screen. The pool rotates among its providers, so this is one of the addresses it calls. Null when no pool
     * provider serves [net].
     */
    fun builtInEndpoint(net: NetworkId): String? =
        pool().filterIsInstance<EvmRpcProvider>().firstNotNullOfOrNull { it.endpoint(net, ProviderConfig()) }
}
