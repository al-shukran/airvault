package app.lumio.data.tx

import app.lumio.data.net.NetworkId
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The period a feed shows. [ANY] is every time. [LAST_24H], [LAST_7D] and [LAST_30D] reach back from now by their [lengthMs]. [DAYS] is the calendar
 * days that [TxFilter.fromDay] and [TxFilter.untilDay] name.
 */
enum class TxPeriod(val lengthMs: Long?) {
    ANY(null),
    LAST_24H(DAY_MS),
    LAST_7D(7 * DAY_MS),
    LAST_30D(30 * DAY_MS),
    DAYS(null),
}

/**
 * What the owner chose on the Transactions tab: the search text, the filters, and the sort. A field left at its default does not filter. [toQuery]
 * turns it into the [TxQuery] that the store runs. [amountAbove] and [amountBelow] are whole units of each asset, and both bounds are exclusive.
 */
data class TxFilter(
    val text: String = "",
    val assets: Set<TxAssetRef> = emptySet(),
    val period: TxPeriod = TxPeriod.ANY,
    val fromDay: LocalDate? = null,
    val untilDay: LocalDate? = null,
    val directions: Set<TxDirection> = emptySet(),
    val amountAbove: BigDecimal? = null,
    val amountBelow: BigDecimal? = null,
    val sort: TxSort = TxSort.TIMESTAMP,
    val descending: Boolean = true,
) {
    /** How many filters are set. The search text and the sort are not filters. */
    val activeCount: Int
        get() = listOf(
            assets.isNotEmpty(),
            period != TxPeriod.ANY,
            directions.isNotEmpty(),
            amountAbove != null,
            amountBelow != null,
        ).count { it }

    /**
     * The query of this filter over [networks] at the time [nowMs], with the calendar days read in [zone]. Spam is left out unless [includeSpam]. A
     * [TxPeriod.DAYS] range runs from the start of [fromDay] to the end of [untilDay], both days included.
     */
    fun toQuery(networks: Set<NetworkId>, nowMs: Long, zone: ZoneId, includeSpam: Boolean): TxQuery {
        val fromMs: Long?
        val untilMs: Long?
        when (period) {
            TxPeriod.ANY -> {
                fromMs = null
                untilMs = null
            }
            TxPeriod.DAYS -> {
                fromMs = fromDay?.let { startOf(it, zone) }
                untilMs = untilDay?.let { startOf(it.plusDays(1), zone) }
            }
            TxPeriod.LAST_24H, TxPeriod.LAST_7D, TxPeriod.LAST_30D -> {
                fromMs = nowMs - checkNotNull(period.lengthMs)
                untilMs = null
            }
        }
        return TxQuery(
            networks = networks,
            assets = assets,
            fromMs = fromMs,
            untilMs = untilMs,
            directions = directions,
            amountAbove = amountAbove,
            amountBelow = amountBelow,
            text = text.trim(),
            includeSpam = includeSpam,
            sort = sort,
            descending = descending,
        )
    }

    private fun startOf(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
}
