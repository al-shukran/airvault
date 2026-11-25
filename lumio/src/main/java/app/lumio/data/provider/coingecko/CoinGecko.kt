package app.lumio.data.provider.coingecko

import app.lumio.data.WallClock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import app.lumio.data.price.NativeCoins
import app.lumio.data.provider.FiatQuote
import app.lumio.data.provider.FiatRates
import app.lumio.data.provider.BitcoinMarket
import app.lumio.data.provider.LogoImage
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.MARKET_NET
import app.lumio.data.provider.MarketQuote
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.TrendingCoin
import app.lumio.data.provider.TrendingCoins
import app.lumio.data.provider.TokenLogo
import app.lumio.data.provider.TrendingList
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.fetchBytes
import app.lumio.data.provider.asArray
import app.lumio.data.provider.asObject
import app.lumio.data.provider.decimal
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import app.lumio.data.provider.text
import app.lumio.data.provider.whole
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request as HttpRequest

/**
 * CoinGecko as a PRICE and MARKET provider. The key is optional ([Trait.KEY_OPTIONAL]) and goes in the `x-cg-demo-api-key` header when set, never in the
 * URL. The price of a coin needs the key, so a price is served only while it is set. Bitcoin's market cap and the trending list work without it.
 * Its place against DefiLlama is its priority in the settings.
 *
 * Sources, read on 2026-11-16 and 2026-11-22:
 * - Price. https://docs.coingecko.com/reference/simple-price (the demo copy is at https://docs.coingecko.com/demo/reference/simple-price). It gives
 *   `GET https://api.coingecko.com/api/v3/simple/price?ids={id}&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true`, answered by
 *   `{"{id}":{"usd":n,"usd_24h_change":n,"last_updated_at":seconds}}`. The change is null when the source regards it as stale.
 * - Bitcoin's market cap. The same simple price endpoint with `include_market_cap=true` (in the Demo / Keyless endpoint list, https://docs.coingecko.com/llms.txt)
 *   answers `{"bitcoin":{"usd":n,"usd_market_cap":n,"usd_24h_change":n,"last_updated_at":seconds}}`. It is read without a key; the price of a coin
 *   is served only while a key is set, by this provider's own rule.
 * - Trending. `GET https://api.coingecko.com/api/v3/search/trending` is in the same list. It answers `{"coins":[{"item":{"name","symbol",
 *   "market_cap_rank","data":{"price":n,"price_change_percentage_24h":{"usd":n},..}}},..],..}`. The source gives no time for it.
 * - Fiat. `GET https://api.coingecko.com/api/v3/exchange_rates` is in the Demo API list (https://docs.coingecko.com/llms.txt, read on 2026-11-23),
 *   and answers `{"rates":{"{code}":{"value":n,"type":"fiat"|"crypto",..},..}}`, every currency quoted against Bitcoin. The rate of a currency
 *   per US dollar is its value over the `usd` value.
 * - Logo. `GET https://api.coingecko.com/api/v3/coins/{id}/contract/{contract_address}` (Coin Data by Token Address, in the Demo / Keyless list at
 *   https://docs.coingecko.com/demo/reference/coins-contract-address) answers the token's record, whose `image` object has `thumb`, `small` and `large`
 *   URLs. The image is read from `small`, or `thumb`. Only the EVM mainnets in [PLATFORMS] have an id, and the ids other than `ethereum` are not
 *   confirmed against the asset platforms list; a wrong id fails as a missing logo.
 * - Authentication: https://docs.coingecko.com/demo/reference/authentication names the demo header `x-cg-demo-api-key` on the demo host.
 * - Limits: https://docs.coingecko.com/docs/errors-and-rate-limits states 100 calls a minute on the demo plan, and IP-based limits without a key, with no
 *   figure. Every request counts, including the failed ones. A coin the source does not know is left out of the answer, which is
 *   [ProviderError.Unsupported] here.
 */
class CoinGeckoProvider : Provider {
    override val id: String = "coingecko"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEY_OPTIONAL)

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean = when (op) {
        Ops.PRICE_NATIVE -> keyOf(cfg) != null && NativeCoins.coinOf(net) != null && providerBase(cfg.baseUrl, BASE) != null
        Ops.MARKET_BITCOIN, Ops.MARKET_TRENDING, Ops.MARKET_FIAT -> providerBase(cfg.baseUrl, BASE) != null
        Ops.LOGO_TOKEN -> platformOf(net) != null && providerBase(cfg.baseUrl, BASE) != null
        else -> false
    }

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) KEYLESS_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativePrice -> price(req, cfg, http) as T
            is BitcoinMarket -> bitcoinMarket(cfg, http) as T
            is TrendingCoins -> trending(cfg, http) as T
            is FiatRates -> fiatRates(cfg, http) as T
            is TokenLogo -> logo(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    private suspend fun price(req: NativePrice, cfg: ProviderConfig, http: Http): PriceQuote {
        val entry = simplePrice(checkNotNull(NativeCoins.coinOf(req.net)), marketCap = false, cfg, http)
        val usd = entry["usd"].decimal()
        if (usd <= 0.0) throw ProviderError.Fatal("The price is not a positive number.")
        val change = entry["usd_24h_change"].present()?.decimal()
        val asOfSeconds = entry["last_updated_at"].whole(min = 0L, max = MAX_SECONDS)
        return PriceQuote(usd = usd, change24hPct = change, asOfMs = asOfSeconds * 1_000L)
    }

    /** The simple price entry of [coin], with its market cap when [marketCap] is set. A coin the source does not know is [ProviderError.Unsupported]. */
    private suspend fun simplePrice(coin: String, marketCap: Boolean, cfg: ProviderConfig, http: Http): JsonObject {
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("simple")
            .addPathSegment("price")
            .addQueryParameter("ids", coin)
            .addQueryParameter("vs_currencies", "usd")
            .addQueryParameter("include_24hr_change", "true")
            .addQueryParameter("include_last_updated_at", "true")
            .apply { if (marketCap) addQueryParameter("include_market_cap", "true") }
            .build()
        return answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null))[coin] as? JsonObject ?: throw ProviderError.Unsupported()
    }

    /** Bitcoin's market capitalisation in USD, its 24-hour price change and the time the source gives. */
    private suspend fun bitcoinMarket(cfg: ProviderConfig, http: Http): MarketQuote {
        val entry = simplePrice(checkNotNull(NativeCoins.coinOf(MARKET_NET)), marketCap = true, cfg, http)
        val capUsd = entry["usd_market_cap"].decimal()
        if (capUsd <= 0.0) throw ProviderError.Fatal("The market cap is not a positive number.")
        val change = entry["usd_24h_change"].present()?.decimal()
        val asOfSeconds = entry["last_updated_at"].whole(min = 0L, max = MAX_SECONDS)
        return MarketQuote(capUsd = capUsd, change24hPct = change, asOfMs = asOfSeconds * 1_000L)
    }

    /** The coins the source lists as trending, in its order. Each coin's price and change are null when the source gives none. */
    private suspend fun trending(cfg: ProviderConfig, http: Http): TrendingList {
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder().addPathSegment("search").addPathSegment("trending").build()
        val coins = answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null))["coins"].asArray().map { element ->
            val item = element.asObject()["item"].asObject()
            val data = item["data"].present()?.asObject()
            TrendingCoin(
                name = item["name"].text(),
                symbol = item["symbol"].text(),
                rank = item["market_cap_rank"].present()?.whole(min = 1L, max = MAX_RANK)?.toInt(),
                priceUsd = data?.get("price").present()?.decimal(),
                change24hPct = data?.get("price_change_percentage_24h").present()?.asObject()?.get("usd").present()?.decimal(),
            )
        }
        return TrendingList(coins = coins, asOfMs = WallClock.nowMs())
    }

    /**
     * The fiat table of the source, as units of each fiat currency per US dollar. The source quotes every currency against Bitcoin, so each rate is its
     * value divided by the US dollar's. A row with no positive value is a malformed answer.
     */
    private suspend fun fiatRates(cfg: ProviderConfig, http: Http): FiatQuote {
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder().addPathSegment("exchange_rates").build()
        val rates = answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null))["rates"].asObject()
        val usdValue = rates["usd"].asObject()["value"].decimal()
        if (usdValue <= 0.0) throw ProviderError.Fatal("The US dollar rate is not a positive number.")
        val perUsd = rates.filterValues { it.asObject()["type"].text() == "fiat" }.map { (code, row) ->
            val value = row.asObject()["value"].decimal()
            if (value <= 0.0) throw ProviderError.Fatal("A fiat rate is not a positive number.")
            code.uppercase() to value / usdValue
        }.toMap()
        return FiatQuote(perUsd = perUsd, asOfMs = WallClock.nowMs())
    }

    /**
     * The logo of the token that [req] names: the image that its record names, read from the image's own URL. The record and the image are read
     * without the key, since the image is served from a host that takes none. A record the source does not have is [ProviderError.NotFound]. The
     * image must be HTTPS, and it is charged to the rate bucket as the second request of the call.
     */
    private suspend fun logo(req: TokenLogo, cfg: ProviderConfig, http: Http): LogoImage {
        val platform = checkNotNull(platformOf(req.net))
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("coins")
            .addPathSegment(platform)
            .addPathSegment("contract")
            .addPathSegment(req.contract.lowercase())
            .build()
        val image = answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null, notFoundIsAnswer = true))["image"].asObject()
        val imageUrl = (image["small"].present() ?: image["thumb"]).text().toHttpUrlOrNull()?.takeIf { it.scheme == "https" }
            ?: throw ProviderError.Fatal("The logo URL is not an HTTPS URL.")
        http.charge()
        return LogoImage(fetchBytes(http.noRedirects(), HttpRequest.Builder().url(imageUrl).get().build(), LOGO_LIMIT_BYTES))
    }

    /** The request for [url], with the key in its header when a key is set. */
    private fun request(cfg: ProviderConfig, url: HttpUrl): HttpRequest =
        HttpRequest.Builder().url(url).get().apply { keyOf(cfg)?.let { header("x-cg-demo-api-key", it) } }.build()

    private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }

    /** The CoinGecko platform of the EVM mainnet [net], or null for a network that has none, such as a testnet. */
    private fun platformOf(net: NetworkId): String? = if (net.testnet || !net.isEvm) null else PLATFORMS[Networks.evmIdOf(net.chain)]

    /** This element, or null when it is absent or JSON null. */
    private fun JsonElement?.present(): JsonElement? = this?.takeUnless { it is JsonNull }
}

private const val BASE = "https://api.coingecko.com/api/v3/"

/** The CoinGecko platform of each EVM mainnet, by chain ID. */
private val PLATFORMS: Map<Long, String> = mapOf(
    1L to "ethereum",
    10L to "optimistic-ethereum",
    56L to "binance-smart-chain",
    137L to "polygon-pos",
    8453L to "base",
    42161L to "arbitrum-one",
    43114L to "avalanche",
)

/** The largest logo image that is read, in bytes. A larger image is a fatal answer. */
private const val LOGO_LIMIT_BYTES = 512L * 1024L

/** The largest time the source may give, in seconds. It is far in the future, so a value above it is a malformed answer. */
private const val MAX_SECONDS = 4_000_000_000L

/** The largest market cap rank the source may give. A rank above it is a malformed answer. */
private const val MAX_RANK = 100_000_000L

/** Without a key the limit is shared by IP and undocumented, so this stays at 30 requests a minute. */
private val KEYLESS_LIMITS = Limits(rps = 0.5, burst = 2, maxConcurrent = 1)

/** The demo plan allows 100 calls a minute, about 1.67 a second. This stays under it. */
private val KEYED_LIMITS = Limits(rps = 1.5, burst = 3, maxConcurrent = 2)
