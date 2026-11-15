package app.lumio.data.price

import app.lumio.data.cache.Cache
import app.lumio.data.cache.Codec
import app.lumio.data.cache.CachePolicy
import app.lumio.data.cache.jsonCodec
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.PriceQuote
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How long a fresh price is reused from memory before the next call. */
const val PRICE_TTL_MS = 60_000L

/**
 * A USD price of a network's native coin: [usd] per coin, the change over 24 hours in percent ([change24hPct] is null when the source gives none),
 * [asOfMs] the time the source gives for it, and [stale]. A stale price is the last one kept, returned because no source answered now. A price
 * that is not stale came from a source, or from memory within [PRICE_TTL_MS].
 */
data class Price(val usd: Double, val change24hPct: Double?, val asOfMs: Long, val stale: Boolean)

/**
 * The USD prices of the native coins. A network is its own asset key, since each network has one native coin. Token prices will need their own key
 * when they are priced.
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
    /**
     * The price of each network in [nets] that has one, keyed by network. A network with no price, such as a testnet or a network with no price coin,
     * is absent from the map. A network whose sources all fail, and that has no kept price, is absent too.
     */
    suspend fun prices(nets: Collection<NetworkId>, priority: Priority = Priority.VISIBLE): Map<NetworkId, Price> = coroutineScope {
        nets.toSet().map { net -> async { priceOf(net, priority)?.let { net to it } } }.awaitAll().filterNotNull().toMap()
    }

    private suspend fun priceOf(net: NetworkId, priority: Priority): Price? {
        val coin = NativeCoins.coinOf(net) ?: return null
        val key = "native-price:$coin"
        val codec = jsonCodec<PriceQuote>()
        val store = cache()
        // A price served from memory is returned as it is. Only a live answer from a source is written as the last price kept.
        store?.get(CachePolicy.ShortTtl(PRICE_TTL_MS), key)?.let { decodeOrNull(codec, it.value) }?.let { return it.toPrice(stale = false) }
        return try {
            val quote = scheduler.run(NativePrice(net), priority)
            store?.put(CachePolicy.ShortTtl(PRICE_TTL_MS), key, codec.encode(quote))
            store?.put(CachePolicy.LastKnown, key, codec.encode(quote))
            quote.toPrice(stale = false)
        } catch (e: ProviderError) {
            store?.get(CachePolicy.LastKnown, key)?.let { decodeOrNull(codec, it.value) }?.toPrice(stale = true)
        }
    }

    /** The decoded value, or null when the stored bytes do not decode: a value that does not decode is a miss. */
    private fun decodeOrNull(codec: Codec<PriceQuote>, bytes: ByteArray): PriceQuote? = runCatching { codec.decode(bytes) }.getOrNull()

    private fun PriceQuote.toPrice(stale: Boolean): Price = Price(usd = usd, change24hPct = change24hPct, asOfMs = asOfMs, stale = stale)
}
