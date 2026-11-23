package app.lumio.data.provider.coingecko

import app.lumio.data.WallClock
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import app.lumio.data.price.NativeCoins
import app.lumio.data.provider.GlobalMarket
import app.lumio.data.provider.LogoImage
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
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
 * URL. The price of a coin needs the key, so a price is served only while it is set. The whole market's figures and the trending list work without it.
 * Its place against DefiLlama is its priority in the settings.
 *
 * Sources, read on 2026-11-16 and 2026-11-22:
 * - Price. https://docs.coingecko.com/reference/simple-price (the demo copy is at https://docs.coingecko.com/demo/reference/simple-price). It gives
 *   `GET https://api.coingecko.com/api/v3/simple/price?ids={id}&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true`, answered by
 *   `{"{id}":{"usd":n,"usd_24h_change":n,"last_updated_at":seconds}}`. The change is null when the source regards it as stale.
 * - Market. `GET https://api.coingecko.com/api/v3/global` is in the Demo / Keyless endpoint list (https://docs.coingecko.com/llms.txt). It answers
 *   `{"data":{"total_market_cap":{"usd":n,..},"market_cap_change_percentage_24h_usd":n,"updated_at":seconds,..}}`.
 * - Trending. `GET https://api.coingecko.com/api/v3/search/trending` is in the same list. It answers `{"coins":[{"item":{"name","symbol",
 *   "market_cap_rank","data":{"price":n,"price_change_percentage_24h":{"usd":n},..}}},..],..}`. The source gives no time for it.
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
        Ops.MARKET_GLOBAL, Ops.MARKET_TRENDING -> providerBase(cfg.baseUrl, BASE) != null
        Ops.LOGO_TOKEN -> platformOf(net) != null && providerBase(cfg.baseUrl, BASE) != null
        else -> false
    }

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) KEYLESS_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativePrice -> price(req, cfg, http) as T
            is GlobalMarket -> global(cfg, http) as T
            is TrendingCoins -> trending(cfg, http) as T
            is TokenLogo -> logo(req, cfg, http) as T
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
        val entry = answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null))[coin] as? JsonObject
            ?: throw ProviderError.Unsupported()
        val usd = entry["usd"].decimal()
        if (usd <= 0.0) throw ProviderError.Fatal("The price is not a positive number.")
        val change = entry["usd_24h_change"].present()?.decimal()
        val asOfSeconds = entry["last_updated_at"].whole(min = 0L, max = MAX_SECONDS)
        return PriceQuote(usd = usd, change24hPct = change, asOfMs = asOfSeconds * 1_000L)
    }

    /** The whole market's total market cap in USD, its 24-hour change and the time the source gives. */
    private suspend fun global(cfg: ProviderConfig, http: Http): MarketQuote {
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder().addPathSegment("global").build()
        val data = answerObject(fetchText(http.noRedirects(), request(cfg, url), banMs = null))["data"].asObject()
        val totalUsd = data["total_market_cap"].asObject()["usd"].decimal()
        if (totalUsd <= 0.0) throw ProviderError.Fatal("The market cap is not a positive number.")
        val change = data["market_cap_change_percentage_24h_usd"].present()?.decimal()
        val asOfSeconds = data["updated_at"].whole(min = 0L, max = MAX_SECONDS)
        return MarketQuote(totalUsd = totalUsd, change24hPct = change, asOfMs = asOfSeconds * 1_000L)
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
