package app.lumio.data.provider.blockscout

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.explorer.explorerBalance
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import java.math.BigInteger
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/**
 * Blockscout as a DATA provider. It serves the native balance of each chain in [BASES] from that chain's hosted instance. It is keyless: it sends no key.
 *
 * Sources, read on 2026-11-15:
 * - Endpoint and answer: https://docs.blockscout.com/devs/apis/rpc/account. It gives `GET {instance}/api?module=account&action=balance&address={address}`,
 *   answered by `{"message":"OK","result":"663046792267785498951364","status":"1"}`.
 * - Live probe on 2026-11-15: https://eth.blockscout.com/api answered that shape with a balance. An invalid address answered
 *   `{"message":"Invalid address hash","result":null,"status":"0"}`.
 * - Limits: https://docs.blockscout.com/devs/apis/requests-and-limits. The default for unkeyed traffic is 300 requests a minute per IP. The page does not
 *   say that figure applies to hosted instances, so the rate here stays well under it.
 * - Not listed: base.blockscout.com and arbitrum.blockscout.com answered with a bot-check page, and gnosis.blockscout.com and optimism.blockscout.com
 *   redirected to other domains. None of the four could be read, so none is mapped.
 * - No key is sent. https://docs.blockscout.com/devs/apis/rpc does not say how a key is passed, so no key parameter is guessed. The limits page says
 *   per-instance API access will be deprecated soon, so this plugin may need replacing.
 */
class BlockscoutProvider(private val bases: Map<String, String> = BASES) : Provider {
    override val id: String = "blockscout"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean = op == Op.NATIVE_BALANCE && instanceOf(net) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    private suspend fun balance(req: NativeBalance, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = checkNotNull(instanceOf(req.net)).newBuilder()
            .addPathSegment("api")
            .addQueryParameter("module", "account")
            .addQueryParameter("action", "balance")
            .addQueryParameter("address", req.address)
            .build()
        return explorerBalance(fetchText(http.noRedirects(), HttpRequest.Builder().url(url).get().build(), banMs = null))
    }

    /** The instance that serves [net], or null when Blockscout hosts none for it, or the address it has is not accepted. */
    private fun instanceOf(net: NetworkId): HttpUrl? =
        if (net.family == Family.EVM && !net.testnet) providerBase(bases[net.chain], "") else null

    companion object {
        /** The hosted instance of each EVM mainnet, by chain ID. Only Ethereum was read on 2026-11-15, see the KDoc. */
        val BASES: Map<String, String> = mapOf("1" to "https://eth.blockscout.com")
    }
}

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** Unkeyed traffic is limited to 300 requests a minute per IP, so this stays at one a second, one call at a time. */
private val LIMITS = Limits(rps = 1.0, burst = 2, maxConcurrent = 1)
