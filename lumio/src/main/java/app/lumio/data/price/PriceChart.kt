package app.lumio.data.price

import app.lumio.data.asset.AssetId
import kotlinx.serialization.Serializable

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

/**
 * A time frame of the price chart, as the asset page's selector offers it. [label] is what the selector shows, and it is also the interval of one point,
 * in the form the price endpoint takes it. The chart draws [points] points, [periodMs] apart, and the last point is the time asked for, so the window
 * runs from `points - 1` intervals before it. Each frame has a fixed count of points that the source answers densely. `1h` stops at a day, because
 * the source's hourly points for some coins go missing beyond that. `1m` is not offered: the source answers it with few and irregular points.
 */
enum class ChartFrame(val label: String, val periodMs: Long, val points: Int) {
    M5("5m", 5 * MINUTE_MS, 48),
    M15("15m", 15 * MINUTE_MS, 48),
    H1("1h", HOUR_MS, 24),
    H4("4h", 4 * HOUR_MS, 48),
    D1("1d", DAY_MS, 48),
    ;

    /** The time from the first point to the last one, in milliseconds. */
    val windowMs: Long get() = periodMs * (points - 1)

    /** The query of this frame at [nowMs]: the window's start in unix seconds, then the number of points and their interval. */
    fun query(nowMs: Long): ChartQuery = ChartQuery(startSec = (nowMs - windowMs) / 1_000L, span = points, period = label)
}

/** The chart endpoint's query: the start in unix seconds, the number of points (`span`), and the spacing between them (`period`). */
data class ChartQuery(val startSec: Long, val span: Int, val period: String)

/** One price of a chart: [usd] per coin at [timeMs], in epoch milliseconds. */
@Serializable
data class PricePoint(val timeMs: Long, val usd: Double)

/**
 * A price chart in time order. [points] is empty when the source has no price in the window, which is an answer and not a failure. [low] and [high]
 * are the least and the greatest price, and [rising] says whether the last point is at or above the first.
 */
@Serializable
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
