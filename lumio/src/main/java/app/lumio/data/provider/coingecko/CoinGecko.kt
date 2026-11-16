package app.lumio.data.provider.coingecko

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.price.NativeCoins
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.Op
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.decimal
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import app.lumio.data.provider.whole
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Request as HttpRequest

/**
 * CoinGecko as a PRICE provider. It needs a demo key from the provider's settings, so it is eligible only while that key is set and the provider is
 * enabled ([Trait.KEY_REQUIRED]). Its place against DefiLlama is its priority in the settings. The key goes in the `x-cg-demo-api-key` header, never in
 * the URL.
 *
 * Sources, read on 2026-11-16:
 * - Endpoint and fields: https://docs.coingecko.com/reference/simple-price (the demo copy is at https://docs.coingecko.com/demo/reference/simple-price).
 *   It gives `GET https://api.coingecko.com/api/v3/simple/price?ids={id}&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true`,
 *   answered by `{"{id}":{"usd":n,"usd_24h_change":n,"last_updated_at":seconds}}`. The change is null when the source regards it as stale.
 * - Authentication: https://docs.coingecko.com/demo/reference/authentication names the demo header `x-cg-demo-api-key` on the demo host.
 * - Limits: https://docs.coingecko.com/docs/errors-and-rate-limits states 100 calls a minute on the demo plan, and IP-based limits without a key, with no
 *   figure. Every request counts, including the failed ones. A coin the source does not know is left out of the answer, which is
 *   [ProviderError.Unsupported] here.
 */
class CoinGeckoProvider : Provider {
    override val id: String = "coingecko"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEY_REQUIRED)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        op == Op.PRICE && NativeCoins.coinOf(net) != null && providerBase(cfg.baseUrl, BASE) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) KEYLESS_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativePrice -> price(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    private suspend fun price(req: NativePrice, cfg: ProviderConfig, http: Http): PriceQuote {
        val coin = checkNotNull(NativeCoins.coinOf(req.net))
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("simple")
            .addPathSegment("price")
            .addQueryParameter("ids", coin)
            .addQueryParameter("vs_currencies", "usd")
            .addQueryParameter("include_24hr_change", "true")
            .addQueryParameter("include_last_updated_at", "true")
            .build()
        val request = HttpRequest.Builder()
            .url(url)
            .get()
            .apply { keyOf(cfg)?.let { header("x-cg-demo-api-key", it) } }
            .build()
        val entry = answerObject(fetchText(http.noRedirects(), request, banMs = null))[coin] as? JsonObject
            ?: throw ProviderError.Unsupported()
        val usd = entry["usd"].decimal()
        if (usd <= 0.0) throw ProviderError.Fatal("The price is not a positive number.")
        val change = entry["usd_24h_change"]?.takeUnless { it is JsonNull }?.decimal()
        val asOfSeconds = entry["last_updated_at"].whole(min = 0L, max = MAX_SECONDS)
        return PriceQuote(usd = usd, change24hPct = change, asOfMs = asOfSeconds * 1_000L)
    }

    private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }
}

private const val BASE = "https://api.coingecko.com/api/v3/"

/** The largest time the source may give, in seconds. It is far in the future, so a value above it is a malformed answer. */
private const val MAX_SECONDS = 4_000_000_000L

/** Without a key the limit is shared by IP and undocumented, so this stays at 30 requests a minute. */
private val KEYLESS_LIMITS = Limits(rps = 0.5, burst = 2, maxConcurrent = 1)

/** The demo plan allows 100 calls a minute, about 1.67 a second. This stays under it. */
private val KEYED_LIMITS = Limits(rps = 1.5, burst = 3, maxConcurrent = 2)
