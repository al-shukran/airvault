package app.lumio.data.price

import app.lumio.data.asset.AssetId
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.Codec
import app.lumio.data.cache.jsonCodec
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.FiatQuote
import app.lumio.data.provider.FiatRates
import app.lumio.data.provider.BitcoinMarket
import app.lumio.data.provider.MarketQuote
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.PriceChart
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TokenPrice
import app.lumio.data.provider.TrendingCoins
import app.lumio.data.provider.TrendingList
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How long a fresh price is reused from memory before the next call. */
const val PRICE_TTL_MS = 60_000L

/** How long a kept price chart is reused from memory before it is read again, so a 24-hour chart does not stay stale for the whole session. */
const val CHART_TTL_MS = 300_000L

/** The cache keys of the whole market's figures and of the trending coins. */
internal const val MARKET_KEY = "market-bitcoin"
internal const val TRENDING_KEY = "trending-search"
internal const val FIAT_KEY = "market-fiat"

/**
 * A USD price of a network's native coin: [usd] per coin, the change over 24 hours in percent ([change24hPct] is null when the source gives none),
 * [asOfMs] the time the source gives for it, and [stale]. A stale price is the last one kept, returned because no source answered now. A price
 * that is not stale came from a source, or from memory within [PRICE_TTL_MS].
 */
data class Price(val usd: Double, val change24hPct: Double?, val asOfMs: Long, val stale: Boolean)

/** A market figure as the screens show it: its [value], and [stale] when no source answered now and it is the last one kept. */
data class Kept<T>(val value: T, val stale: Boolean)

/**
 * The USD prices of the native coins and of the ERC-20 tokens that [AssetId] names. A native coin is priced by its network's CoinGecko id, and a token
 * by its contract under its chain's DefiLlama prefix (see [DefiLlamaChains]). An asset with no price source is absent from the answer.
 *
 * A fresh price is reused from memory for [PRICE_TTL_MS]. Each price a source answers is also kept in the open vault's cache, so when no source
 * answers, the last one is returned with [Price.stale] set. While the vault is locked there is no cache, so only a live answer is returned.
 *
 * Privacy: a price request names only the coin, never an address, so a source learns which coins are read and nothing about the wallet.
 *
 * @param scheduler the scheduler that holds the price providers. It routes each request, applies their rate limits and cooldowns, and fails over.
 * @param cache the open vault's cache, or null while the vault is locked.
 */
class PriceRepository(
    private val scheduler: Scheduler,
    private val cache: () -> Cache?,
) {
    /** The charts this session has read, with points. They are public prices and are kept in memory only, for [CHART_TTL_MS]. */
    private val charts = ConcurrentHashMap<ChartKey, KeptChart>()

    /**
     * The price of each network in [nets] that has one, keyed by network. A network with no price, such as a testnet or a network with no price coin,
     * is absent from the map. A network whose sources all fail, and that has no kept price, is absent too.
     */
    suspend fun prices(nets: Collection<NetworkId>, priority: Priority = Priority.VISIBLE): Map<NetworkId, Price> = coroutineScope {
        nets.toSet().map { net -> async { priceOf(AssetId.Native(net), priority)?.let { net to it } } }.awaitAll().filterNotNull().toMap()
    }

    /** The price of each of [assets] that has one, keyed by asset. The rules of [prices] hold for each asset. */
    suspend fun assetPrices(assets: Collection<AssetId>, priority: Priority = Priority.VISIBLE): Map<AssetId, Price> = coroutineScope {
        assets.toSet().map { asset -> async { priceOf(asset, priority)?.let { asset to it } } }.awaitAll().filterNotNull().toMap()
    }

    /** The last price kept in the vault's cache of each of [assets] that has one. It asks no source, so it is stale by definition. */
    suspend fun lastPrices(assets: Collection<AssetId>): Map<AssetId, Price> {
        val store = cache() ?: return emptyMap()
        val codec = jsonCodec<PriceQuote>()
        return assets.toSet().mapNotNull { asset ->
            val key = priceKeyOf(asset) ?: return@mapNotNull null
            store.get(CachePolicy.LastKnown, key)?.let { decodeOrNull(codec, it.value) }?.let { asset to it.toPrice(stale = true) }
        }.toMap()
    }

    /**
     * Whether a switched-on source serves Bitcoin's market figures. Switched off, no market figure is shown, kept or not, so every read below is null.
     */
    fun servesMarket(): Boolean = scheduler.serves(BitcoinMarket())

    /** Whether a switched-on source serves the trending coins. Switched off, no trending list is shown, kept or not. */
    fun servesTrending(): Boolean = scheduler.serves(TrendingCoins())

    /** Whether a switched-on source serves the fiat rates. Switched off, no rate is read, kept or not, and every amount stays in US dollars. */
    fun servesFiat(): Boolean = scheduler.serves(FiatRates())

    /**
     * The fiat rates, from a source or from memory within [PRICE_TTL_MS]. When no source answers, the last ones kept, stale. Null when no source serves
     * them, or none was ever kept and none answers.
     */
    suspend fun fiatRates(priority: Priority = Priority.VISIBLE): Kept<FiatQuote>? =
        if (servesFiat()) kept(FIAT_KEY, jsonCodec<FiatQuote>(), FiatRates(), priority) else null

    /** Bitcoin's market figures, from a source or from memory within [PRICE_TTL_MS]. When no source answers, the last ones kept, stale. */
    suspend fun market(priority: Priority = Priority.VISIBLE): Kept<MarketQuote>? =
        if (servesMarket()) kept(MARKET_KEY, jsonCodec<MarketQuote>(), BitcoinMarket(), priority) else null

    /** The trending coins, from a source or from memory within [PRICE_TTL_MS]. When no source answers, the last list kept, stale. */
    suspend fun trending(priority: Priority = Priority.VISIBLE): Kept<TrendingList>? =
        if (servesTrending()) kept(TRENDING_KEY, jsonCodec<TrendingList>(), TrendingCoins(), priority) else null

    /** The last market figures kept in the vault's cache, with no source asked. Null while the vault is locked, none was kept or no source is on. */
    suspend fun lastMarket(): Kept<MarketQuote>? = if (servesMarket()) lastKept(MARKET_KEY, jsonCodec<MarketQuote>()) else null

    /** The last trending list kept in the vault's cache, with no source asked. Null while the vault is locked, none was kept or no source is on. */
    suspend fun lastTrending(): Kept<TrendingList>? = if (servesTrending()) lastKept(TRENDING_KEY, jsonCodec<TrendingList>()) else null

    /**
     * The USD price points of [asset] over [range], ending at [nowMs]. A series with no points means the source has no price in the window. Null means no
     * source answered, such as a testnet coin or a source that is down. A series with points is kept in memory for the session, and read again once it
     * is [CHART_TTL_MS] old. The request goes through the scheduler, so the price providers' rate limits and cooldowns apply.
     */
    suspend fun chart(asset: AssetId, range: ChartFrame, priority: Priority = Priority.VISIBLE, nowMs: Long = System.currentTimeMillis()): PriceSeries? {
        val key = ChartKey(asset, range)
        charts[key]?.takeIf { nowMs - it.atMs < CHART_TTL_MS }?.let { return it.series }
        val series = try {
            scheduler.run(PriceChart(asset, range, nowMs), priority)
        } catch (e: ProviderError) {
            return null
        }
        if (series.points.isNotEmpty()) {
            charts[key] = KeptChart(series, nowMs)
            // The day chart is also kept in the vault, so Home charts it on a cold start, before any source answers.
            if (range == ChartFrame.H1) dayChartKeyOf(asset)?.let { cache()?.put(CachePolicy.LastKnown, it, jsonCodec<PriceSeries>().encode(series)) }
        }
        return series
    }

    /** The last day chart kept in the vault's cache of each of [assets] that has one. It asks no source, so it is stale by definition. */
    suspend fun lastDayCharts(assets: Collection<AssetId>): Map<AssetId, PriceSeries> {
        val store = cache() ?: return emptyMap()
        val codec = jsonCodec<PriceSeries>()
        return assets.toSet().mapNotNull { asset ->
            val key = dayChartKeyOf(asset) ?: return@mapNotNull null
            store.get(CachePolicy.LastKnown, key)?.let { decodeOrNull(codec, it.value) }?.let { asset to it }
        }.toMap()
    }

    private data class ChartKey(val asset: AssetId, val range: ChartFrame)

    /** A chart as the repository kept it, and the time it was read, in epoch milliseconds. */
    private class KeptChart(val series: PriceSeries, val atMs: Long)

    private suspend fun priceOf(asset: AssetId, priority: Priority): Price? {
        val request = priceRequestOf(asset) ?: return null
        val key = priceKeyOf(asset) ?: return null
        val codec = jsonCodec<PriceQuote>()
        val store = cache()
        // A price served from memory is returned as it is. Only a live answer from a source is written as the last price kept.
        store?.get(CachePolicy.ShortTtl(PRICE_TTL_MS), key)?.let { decodeOrNull(codec, it.value) }?.let { return it.toPrice(stale = false) }
        return try {
            val quote = scheduler.run(request, priority)
            store?.put(CachePolicy.ShortTtl(PRICE_TTL_MS), key, codec.encode(quote))
            store?.put(CachePolicy.LastKnown, key, codec.encode(quote))
            quote.toPrice(stale = false)
        } catch (e: ProviderError) {
            store?.get(CachePolicy.LastKnown, key)?.let { decodeOrNull(codec, it.value) }?.toPrice(stale = true)
        }
    }

    /** A value of [request] kept under [key] for [PRICE_TTL_MS], else asked for; when no source answers, the last value kept, stale. */
    private suspend fun <T> kept(key: String, codec: Codec<T>, request: Request<T>, priority: Priority): Kept<T>? {
        val store = cache()
        store?.get(CachePolicy.ShortTtl(PRICE_TTL_MS), key)?.let { decodeOrNull(codec, it.value) }?.let { return Kept(it, stale = false) }
        return try {
            val value = scheduler.run(request, priority)
            store?.put(CachePolicy.ShortTtl(PRICE_TTL_MS), key, codec.encode(value))
            store?.put(CachePolicy.LastKnown, key, codec.encode(value))
            Kept(value, stale = false)
        } catch (e: ProviderError) {
            lastKept(key, codec)
        }
    }

    /** The last value kept under [key] in the vault's cache, stale, or null when none was kept or the vault is locked. */
    private suspend fun <T> lastKept(key: String, codec: Codec<T>): Kept<T>? =
        cache()?.get(CachePolicy.LastKnown, key)?.let { decodeOrNull(codec, it.value) }?.let { Kept(it, stale = true) }

    /** The decoded value, or null when the stored bytes do not decode: a value that does not decode is a miss. */
    private fun <T> decodeOrNull(codec: Codec<T>, bytes: ByteArray): T? = runCatching { codec.decode(bytes) }.getOrNull()

    private fun PriceQuote.toPrice(stale: Boolean): Price = Price(usd = usd, change24hPct = change24hPct, asOfMs = asOfMs, stale = stale)
}

/** The request that prices [asset], or null when no price source names it. */
internal fun priceRequestOf(asset: AssetId): Request<PriceQuote>? = when (asset) {
    is AssetId.Native -> NativeCoins.coinOf(asset.net)?.let { NativePrice(asset.net) }
    is AssetId.Erc20 -> DefiLlamaChains.prefixOf(asset.net)?.let { TokenPrice(asset.net, asset.contract) }
}

/** The cache key of the day chart of [asset]. Null when it has no price source. */
internal fun dayChartKeyOf(asset: AssetId): String? = priceKeyOf(asset)?.let { "day-chart:$it" }

/** The cache key of the price of [asset]. Null when it has no price source. */
internal fun priceKeyOf(asset: AssetId): String? = when (asset) {
    is AssetId.Native -> NativeCoins.coinOf(asset.net)?.let { "native-price:$it" }
    is AssetId.Erc20 -> DefiLlamaChains.prefixOf(asset.net)?.let { "token-price:$it:${asset.contract}" }
}
