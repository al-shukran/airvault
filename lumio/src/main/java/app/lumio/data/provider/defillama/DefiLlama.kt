package app.lumio.data.provider.defillama

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.price.DefiLlamaChains
import app.lumio.data.price.NativeCoins
import app.lumio.data.price.PricePoint
import app.lumio.data.price.PriceSeries
import app.lumio.data.price.chartCoinOf
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.Op
import app.lumio.data.provider.PriceChart
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TokenPrice
import app.lumio.data.provider.Trait
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.asArray
import app.lumio.data.provider.asObject
import app.lumio.data.provider.decimal
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/**
 * DefiLlama as a PRICE provider. It serves the USD price and the 24-hour change of the native coins that [NativeCoins] names, and of the ERC-20
 * tokens on a chain that [DefiLlamaChains] has a prefix for, and the price chart of the same coins. It is keyless and sends no key. A price request
 * names only the coin, which for a token is its contract, never an owner's address.
 *
 * Sources, read on 2026-11-16:
 * - Documentation: https://api-docs.defillama.com/llms-free.txt, and its OpenAPI file https://api-docs.defillama.com/defillama-openapi-free.json. Both
 *   name the free host as https://api.llama.fi. On that day `api.llama.fi` answered "Route not found" for these two paths, while `coins.llama.fi`
 *   answered the documented shapes, so the default base is https://coins.llama.fi. The documentation page https://defillama.com/docs/api answered a
 *   bot challenge, so it was not read.
 * - `GET {base}/prices/current/coingecko:{id}` answers `{"coins":{"coingecko:{id}":{"price":n,"symbol":"ETH","timestamp":seconds,"confidence":n}}}`.
 *   A coin the source does not price answers `{"coins":{}}`, and that is [ProviderError.Unsupported] here, so the next provider answers.
 * - `GET {base}/percentage/coingecko:{id}?period=24h` answers `{"coins":{"coingecko:{id}":n}}`: the percentage change over the 24 hours before now.
 * - `GET {base}/chart/{coin}?start=&span=&period=` answers `{"coins":{"{coin}":{"symbol":..,"confidence":..,"prices":[{"timestamp":seconds,"price":n}]}}}`.
 *   `start` is the unix time in seconds of the first point, `span` the number of points, and `period` the spacing in candle notation (`1h`, `4h`,
 *   `1d`, `1w`). The OpenAPI file https://api-docs.defillama.com/defillama-openapi-free.json, read on 2026-11-16, gives these three parameters and the
 *   server `https://coins.llama.fi` for this path. Its prose says only that `period` takes "4h"-style values.
 * - Limits: the free docs state none, and they document no 429. A 429 is still read as a rate limit, see [failureFor] in the provider package.
 */
class DefiLlamaProvider : Provider {
    override val id: String = "defillama"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean {
        val priced = when (op) {
            Op.PRICE -> NativeCoins.coinOf(net) != null
            Op.TOKEN_PRICE -> DefiLlamaChains.prefixOf(net) != null
            Op.PRICE_CHART -> NativeCoins.coinOf(net) != null || DefiLlamaChains.prefixOf(net) != null
            else -> false
        }
        return priced && providerBase(cfg.baseUrl, BASE) != null
    }

    override fun defaultLimits(cfg: ProviderConfig): Limits = LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativePrice -> price("coingecko:" + checkNotNull(NativeCoins.coinOf(req.net)), cfg, http) as T
            is TokenPrice -> price(checkNotNull(DefiLlamaChains.prefixOf(req.net)) + ":" + req.contract, cfg, http) as T
            is PriceChart -> chart(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    /** The price of [coin], a `{chain}:{address}` or `coingecko:{id}` id, then its 24-hour change. The second request takes its own token from the call's rate bucket. */
    private suspend fun price(coin: String, cfg: ProviderConfig, http: Http): PriceQuote {
        val base = checkNotNull(providerBase(cfg.baseUrl, BASE))
        val priceUrl = base.newBuilder().addPathSegment("prices").addPathSegment("current").addPathSegment(coin).build()
        val current = answerObject(fetchText(http.noRedirects(), get(priceUrl), banMs = null))
        val entry = current["coins"].asObject()[coin] as? JsonObject ?: throw ProviderError.Unsupported()
        val usd = entry["price"].decimal()
        if (usd <= 0.0) throw ProviderError.Fatal("The price is not a positive number.")
        val seconds = entry["timestamp"].decimal()
        if (seconds <= 0.0) throw ProviderError.Fatal("The price has no time.")

        http.charge()
        val changeUrl = base.newBuilder()
            .addPathSegment("percentage")
            .addPathSegment(coin)
            .addQueryParameter("period", "24h")
            .build()
        val change = answerObject(fetchText(http.noRedirects(), get(changeUrl), banMs = null))["coins"].asObject()[coin]
            ?.takeUnless { it is JsonNull }
            ?.decimal()
        return PriceQuote(usd = usd, change24hPct = change, asOfMs = (seconds * 1_000.0).toLong())
    }

    /**
     * The price points of [req]'s asset over its range, from the chart endpoint, in time order. A coin the source does not know is
     * [ProviderError.Unsupported]. A point with no positive price is dropped, and a window with no points is an empty series, which is an answer.
     */
    private suspend fun chart(req: PriceChart, cfg: ProviderConfig, http: Http): PriceSeries {
        val coin = chartCoinOf(req.asset) ?: throw ProviderError.Unsupported()
        val query = req.range.query(req.nowMs)
        val base = checkNotNull(providerBase(cfg.baseUrl, BASE))
        val chartUrl = base.newBuilder()
            .addPathSegment("chart")
            .addPathSegment(coin)
            .addQueryParameter("start", query.startSec.toString())
            .addQueryParameter("span", query.span.toString())
            .addQueryParameter("period", query.period)
            .build()
        val entry = answerObject(fetchText(http.noRedirects(), get(chartUrl), banMs = null))["coins"].asObject()[coin] as? JsonObject
            ?: throw ProviderError.Unsupported()
        val points = entry["prices"].asArray().map { element ->
            val point = element.asObject()
            PricePoint(timeMs = (point["timestamp"].decimal() * 1_000.0).toLong(), usd = point["price"].decimal())
        }
        return PriceSeries(points.filter { it.usd > 0.0 }.sortedBy { it.timeMs })
    }

    private fun get(url: HttpUrl): HttpRequest = HttpRequest.Builder().url(url).get().build()
}

private const val BASE = "https://coins.llama.fi/"

/**
 * The free host states no limit. Two requests a second, with a burst of four, keeps the source well clear of any limit it has not stated. A price
 * takes two of those requests.
 */
private val LIMITS = Limits(rps = 2.0, burst = 4, maxConcurrent = 2)
