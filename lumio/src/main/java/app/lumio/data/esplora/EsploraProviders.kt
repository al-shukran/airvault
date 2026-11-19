package app.lumio.data.esplora

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait

/** The operations an Esplora server serves. Broadcast is `POST /tx`, which every Esplora server takes. */
private val ESPLORA_OPS = setOf(
    Ops.BTC_ADDRESS_STATS,
    Ops.BTC_ADDRESS_TXS,
    Ops.BTC_UTXOS,
    Ops.BTC_TIP_HEIGHT,
    Ops.BTC_FEE_ESTIMATES,
    Ops.BTC_BLOCK,
    Ops.BTC_TX,
    Ops.BTC_BROADCAST,
)

/**
 * The rate each Esplora server is asked at by default. It is well under the rate that the scan needs for eight accounts, and the scheduler moves a
 * call to another server when one answers 429, so a server's limit costs time and never a failed scan.
 */
private val DEFAULT_LIMITS = Limits(rps = 3.0, burst = 6, maxConcurrent = 2)

/**
 * A provider that serves Bitcoin through an Esplora server. A plugin names the base URL of each network in [baseUrl]. [EsploraProtocol] makes
 * the requests, so the plugins differ only in their URLs and traits.
 */
abstract class EsploraProvider : Provider {
    override val kind: Kind = Kind.DATA

    /** The base URL that serves [net] under [cfg], or null when this provider does not serve [net]. */
    protected abstract fun baseUrl(net: NetworkId, cfg: ProviderConfig): String?

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean = op in ESPLORA_OPS && baseUrl(net, cfg) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = DEFAULT_LIMITS

    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        val base = baseUrl(req.net, cfg) ?: throw ProviderError.Unsupported()
        return EsploraProtocol.execute(req, base, http)
    }
}

/**
 * mempool.space, keyless: it never receives a key, and the scan never sends one. Mainnet is served under https://mempool.space/api. Testnet4 is
 * served under https://mempool.space/testnet4/api, the path that Lumio's settings have always named. Its rate limits are described at
 * https://mempool.space/docs/api/rest, where an excess answers HTTP 429.
 */
class MempoolProvider(private val bases: Map<String, String> = BASES) : EsploraProvider() {
    override val id: String = "mempool"
    override val traits: Set<Trait> = setOf(Trait.KEYLESS, Trait.ESPLORA_COMPAT)

    override fun baseUrl(net: NetworkId, cfg: ProviderConfig): String? = if (net.isBtc) bases[net.name] else null

    companion object {
        /** The public base URL of each Bitcoin chain. */
        val BASES: Map<String, String> = mapOf(
            "mainnet" to "https://mempool.space/api",
            "testnet4" to "https://mempool.space/testnet4/api",
        )
    }
}

/**
 * blockstream.info, keyless. Its API document lists https://blockstream.info/api/ for mainnet and https://blockstream.info/testnet4/api/ for
 * testnet4. The document names no API key and no auth header, so the plugin sends neither
 * (https://github.com/Blockstream/esplora/blob/master/API.md).
 */
class BlockstreamProvider(private val bases: Map<String, String> = BASES) : EsploraProvider() {
    override val id: String = "blockstream"
    override val traits: Set<Trait> = setOf(Trait.KEYLESS, Trait.ESPLORA_COMPAT)

    override fun baseUrl(net: NetworkId, cfg: ProviderConfig): String? = if (net.isBtc) bases[net.name] else null

    companion object {
        /** The public base URL of each Bitcoin chain. */
        val BASES: Map<String, String> = mapOf(
            "mainnet" to "https://blockstream.info/api",
            "testnet4" to "https://blockstream.info/testnet4/api",
        )
    }
}

/**
 * The owner's own Esplora server for one Bitcoin chain, [chain] ("mainnet" or "testnet4"). Its base URL is the `baseUrl` of its settings, and it
 * serves nothing until one is set. While it is enabled it is the only provider of its chain ([app.lumio.data.provider.Trait.OWN_SERVER]), so the
 * owner's addresses are never sent to a public server.
 */
class CustomEsploraProvider(private val chain: String) : EsploraProvider() {
    override val id: String = idFor(chain)
    override val traits: Set<Trait> = setOf(Trait.ESPLORA_COMPAT, Trait.OWN_SERVER)

    override fun baseUrl(net: NetworkId, cfg: ProviderConfig): String? =
        if (net.isBtc && net.name == chain) cfg.baseUrl?.takeIf { it.isNotBlank() } else null

    companion object {
        /** The prefix of every owner's server id. */
        const val ID_PREFIX = "custom-btc-"

        /** The provider id of the owner's server for [chain]. */
        fun idFor(chain: String): String = ID_PREFIX + chain
    }
}
