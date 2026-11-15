package app.lumio.data.provider.routescan

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
import okhttp3.Request as HttpRequest

/**
 * Routescan as a DATA provider. It serves the native balance of the EVM mainnets in [CHAINS], through its Etherscan-compatible account API. It works
 * without a key. An optional key goes in the `apikey` header, so it never sits in a URL.
 *
 * Sources, read on 2026-11-15:
 * - Endpoint and parameters: https://docs.routescan.io/api/etherscan/accounts. It gives
 *   `GET https://api.routescan.io/v2/network/mainnet/evm/{chainId}/etherscan/api?module=account&action=balance&address={address}&tag=latest`, and the
 *   optional key as the header `apikey: {key}`.
 * - Limits: https://docs.routescan.io/plans-and-limits/rate-limits. Keyless is 2 requests a second and 10,000 a day. A registered free key is 5 a second
 *   and 100,000 a day. The Data API and the Etherscan-compatible API share these limits.
 * - Chains: probed live on 2026-11-15 with no key. Chains 1, 5000 and 43114 answered with a balance. Chains 8453, 10, 42161, 137, 56, 100, 59144, 42220
 *   and 146 answered "chain not supported". Keyed coverage was not probed, so [CHAINS] holds only the chains that answered without a key.
 *
 * Not served: testnets, and every operation except the native balance. The docs name no answer for an exceeded limit, so a 429 with a Retry-After and the
 * "rate limit" answers that [explorerBalance] reads are the rate limits.
 */
class RoutescanProvider : Provider {
    override val id: String = "routescan"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        op == Op.NATIVE_BALANCE && net.family == Family.EVM && !net.testnet && net.chain in CHAINS && providerBase(cfg.baseUrl, BASE) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) KEYLESS_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    private suspend fun balance(req: NativeBalance, cfg: ProviderConfig, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("v2")
            .addPathSegment("network")
            .addPathSegment("mainnet")
            .addPathSegment("evm")
            .addPathSegment(req.net.chain)
            .addPathSegment("etherscan")
            .addPathSegment("api")
            .addQueryParameter("module", "account")
            .addQueryParameter("action", "balance")
            .addQueryParameter("address", req.address)
            .addQueryParameter("tag", "latest")
            .build()
        val request = HttpRequest.Builder()
            .url(url)
            .get()
            .apply { keyOf(cfg)?.let { header("apikey", it) } }
            .build()
        return explorerBalance(fetchText(http.noRedirects(), request, banMs = null))
    }

    private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }
}

private const val BASE = "https://api.routescan.io/"

/** The EVM mainnets that the keyless API answered in the probe of 2026-11-15, by chain ID. */
private val CHAINS: Set<String> = setOf("1", "5000", "43114")

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** Keyless is 2 requests a second, so this stays at half of that, one call at a time. */
private val KEYLESS_LIMITS = Limits(rps = 1.0, burst = 2, maxConcurrent = 1)

/** A registered free key is 5 requests a second, so this stays under that. */
private val KEYED_LIMITS = Limits(rps = 3.0, burst = 3, maxConcurrent = 2)
