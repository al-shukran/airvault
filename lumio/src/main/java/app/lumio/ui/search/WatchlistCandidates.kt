package app.lumio.ui.search

import app.lumio.data.provider.MarketCoin
import app.lumio.search.matching
import app.lumio.watchlist.CoinIndex
import app.lumio.watchlist.IndexedCoin
import app.lumio.watchlist.TopList
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchedLabel

/**
 * One asset Add to Watchlist offers: the source's [coinId], the [asset] Lumio watches for it, its [symbol] and its [name] as Lumio shows it, its [rank]
 * (null when no market row was read for it), the URL of its logo [image], and how many other coins of the results share its name ([sameName]).
 */
data class Candidate(
    val coinId: String,
    val asset: WatchedAsset,
    val name: String,
    val symbol: String,
    val rank: MarketRank?,
    val image: String?,
    val sameName: Int = 0,
) {
    /** What the watchlist keeps with the asset when it is starred, so Home names and draws it without reading the source again. */
    val label: WatchedLabel get() = WatchedLabel(symbol = symbol, name = name, image = image)
}

/**
 * The names Lumio gives a coin where the market source's name differs: the locked kit titles USDC "USD Coin". Every other coin keeps the source's name.
 */
private val NAME_OVERRIDES: Map<String, String> = mapOf("usd-coin" to "USD Coin")

/** The name Lumio shows for the market source's coin [id] named [name]. */
fun coinDisplayName(id: String, name: String): String = NAME_OVERRIDES[id] ?: name

/** The rank a market row gives: its place, or Unranked when the source ranks it nowhere. */
private fun rankOf(rank: Int?): MarketRank = rank?.let { MarketRank.Place(it) } ?: MarketRank.Unranked

/** The candidate of [coin], with its [rank] and logo [image] when they are known. */
private fun candidateOf(coin: IndexedCoin, rank: MarketRank?, image: String?, sameName: Int = 0) =
    Candidate(coin.id, coin.asset, coinDisplayName(coin.id, coin.name), coin.symbol.uppercase(), rank, image, sameName)

/** Top by Market Cap as rows, in the source's order. */
fun topCandidates(top: TopList): List<Candidate> = top.coins.map { candidateOf(it.coin, rankOf(it.rank), it.image) }

/** The most results a search shows, and the most whose market rows it reads. */
const val MAX_RESULTS = 50

/**
 * A search: the coins of [index] that [query] finds by name or ticker, as the search finds them, one row per name: the best-known coin of those that
 * share it, with the count of the others. The [MAX_RESULTS] best matches are kept: an exact name or ticker first, then one that starts with the query,
 * then the rest, each by its rank. A coin's rank comes from [top], else from [markets], the market rows read for the results; rows go by rank, the
 * unranked after the ranked, and a coin whose rank is not read yet last.
 */
fun searchCandidates(query: String, index: CoinIndex, top: TopList?, markets: Map<String, MarketCoin>): List<Candidate> {
    val needle = query.trim()
    val ranked = top?.coins?.associateBy { it.coin.id }.orEmpty()
    fun rank(coin: IndexedCoin): MarketRank? = ranked[coin.id]?.let { rankOf(it.rank) } ?: markets[coin.id]?.let { rankOf(it.rank) }
    fun image(coin: IndexedCoin): String? = ranked[coin.id]?.image ?: markets[coin.id]?.image
    return matching(needle, index.entries).map { it.target }
        .groupBy { it.name.lowercase() }.values
        .map { group ->
            val best = group.minWith(compareBy { order(rank(it)) })
            candidateOf(best, rank(best), image(best), sameName = group.size - 1) to matchKind(needle, best)
        }
        .sortedWith(compareBy({ it.second }, { order(it.first.rank) }))
        .take(MAX_RESULTS)
        .map { it.first }
        .sortedBy { order(it.rank) }
}

/** The coins of [results] whose rank is not known yet, whose market rows a search reads: at most [MAX_RESULTS]. */
fun unrankedIds(results: List<Candidate>): List<String> = results.filter { it.rank == null }.map { it.coinId }.take(MAX_RESULTS)

/** How well [coin] matches [needle]: 0 for its exact name or ticker, 1 when one starts with it, 2 otherwise. */
private fun matchKind(needle: String, coin: IndexedCoin): Int = when {
    coin.name.equals(needle, ignoreCase = true) || coin.symbol.equals(needle, ignoreCase = true) -> 0
    coin.name.startsWith(needle, ignoreCase = true) || coin.symbol.startsWith(needle, ignoreCase = true) -> 1
    else -> 2
}

/** The order of a rank: a place first, smallest first, then Unranked, then no rank read. */
private fun order(rank: MarketRank?): Long = when (rank) {
    is MarketRank.Place -> rank.place.toLong()
    MarketRank.Unranked -> Long.MAX_VALUE - 1
    null -> Long.MAX_VALUE
}
