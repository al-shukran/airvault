package app.lumio.data.price

import app.lumio.data.asset.AssetId

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/**
 * The span of a price chart, as the range selector offers it. [label] is what the selector shows. The chart draws [points] points, [periodMs] apart,
 * and [period] is that same spacing in DefiLlama's candle notation, where `H` is an hour, `D` a day and `W` a week. The last point is the time asked
 * for, so the window runs from `points - 1` periods before it: 24 hourly points cover 24 hours, 7 four-hourly days cover 7 days, and so on.
 */
enum class ChartRange(val label: String, val period: String, val periodMs: Long, val points: Int) {
    H24("24h", "1h", HOUR_MS, 25),
    D7("7d", "4h", 4 * HOUR_MS, 43),
    D30("30d", "1d", DAY_MS, 31),
    Y1("1y", "1d", DAY_MS, 366),
    ;

    /** The time from the first point to the last one, in milliseconds. */
    val windowMs: Long get() = periodMs * (points - 1)

    /** The query of this range at [nowMs]: the window's start in unix seconds, then the number of points and their period. */
    fun query(nowMs: Long): ChartQuery = ChartQuery(startSec = (nowMs - windowMs) / 1_000L, span = points, period = period)
}

/** The chart endpoint's query: the start in unix seconds, the number of points (`span`), and the spacing between them (`period`). */
data class ChartQuery(val startSec: Long, val span: Int, val period: String)

/** One price of a chart: [usd] per coin at [timeMs], in epoch milliseconds. */
data class PricePoint(val timeMs: Long, val usd: Double)

/**
 * A price chart in time order. [points] is empty when the source has no price in the window, which is an answer and not a failure. [low] and [high]
 * are the least and the greatest price, and [rising] says whether the last point is at or above the first.
 */
data class PriceSeries(val points: List<PricePoint>) {
    val low: Double? get() = points.minOfOrNull { it.usd }

    val high: Double? get() = points.maxOfOrNull { it.usd }

    val rising: Boolean get() = points.size < 2 || points.last().usd >= points.first().usd
}

/**
 * The DefiLlama coin id that charts [asset]: `coingecko:{id}` for a native coin, and `{chain}:{contract}` for a token. It is the same id the price
 * reads use. Null when DefiLlama has no id for [asset], such as a testnet coin.
 */
internal fun chartCoinOf(asset: AssetId): String? = when (asset) {
    is AssetId.Native -> NativeCoins.coinOf(asset.net)?.let { "coingecko:$it" }
    is AssetId.Erc20 -> DefiLlamaChains.prefixOf(asset.net)?.let { "$it:${asset.contract}" }
}
