package app.lumio.data.tx

import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter and the sort of the Transactions tab, turned into the store's query. Each period preset, each day range, and the combinations of filters
 * with a sort are checked here, so the tab asks the store for the rows that the owner sees.
 */
class TxFilterTest {
    private val eth = NetworkId(Family.EVM, "1", testnet = false)
    private val networks = setOf(eth)
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val usdc = TxAssetRef(eth, "0xabc")

    private fun startOf(date: LocalDate, zone: ZoneOffset): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun query(filter: TxFilter, includeSpam: Boolean = false, zone: ZoneOffset = ZoneOffset.UTC) =
        filter.toQuery(networks, now, zone, includeSpam)

    @Test
    fun anyPeriodHasNoTimeBounds() {
        val q = query(TxFilter())
        assertNull(q.fromMs)
        assertNull(q.untilMs)
    }

    @Test
    fun aDurationPresetReachesBackFromNowWithoutAnUpperBound() {
        assertEquals(now - day, query(TxFilter(period = TxPeriod.LAST_24H)).fromMs)
        assertEquals(now - 7 * day, query(TxFilter(period = TxPeriod.LAST_7D)).fromMs)
        assertEquals(now - 30 * day, query(TxFilter(period = TxPeriod.LAST_30D)).fromMs)
        assertNull(query(TxFilter(period = TxPeriod.LAST_7D)).untilMs)
    }

    @Test
    fun aDayRangeCoversBothDaysInFullInTheZoneOfTheOwner() {
        val filter = TxFilter(period = TxPeriod.DAYS, fromDay = LocalDate.of(2026, 11, 1), untilDay = LocalDate.of(2026, 11, 3))
        val q = query(filter)
        assertEquals(startOf(LocalDate.of(2026, 11, 1), ZoneOffset.UTC), q.fromMs)
        assertEquals(startOf(LocalDate.of(2026, 11, 4), ZoneOffset.UTC), q.untilMs)
    }

    @Test
    fun aDayRangeReadsItsDaysInTheOwnersZone() {
        val east = ZoneOffset.ofHours(2)
        val filter = TxFilter(period = TxPeriod.DAYS, fromDay = LocalDate.of(2026, 11, 1))
        assertEquals(startOf(LocalDate.of(2026, 11, 1), east), query(filter, zone = east).fromMs)
        assertNull(query(filter, zone = east).untilMs)
    }

    @Test
    fun aDayPeriodWithNoDaysHasNoTimeBounds() {
        val q = query(TxFilter(period = TxPeriod.DAYS))
        assertNull(q.fromMs)
        assertNull(q.untilMs)
    }

    @Test
    fun theSearchIsTrimmedAndSpamFollowsTheSetting() {
        val shown = query(TxFilter(text = "  0xabc  "), includeSpam = true)
        assertEquals("0xabc", shown.text)
        assertTrue(shown.includeSpam)
        assertFalse(query(TxFilter(), includeSpam = false).includeSpam)
    }

    @Test
    fun theFiltersPassThroughWithTheSortAndItsOrder() {
        val filter = TxFilter(
            assets = setOf(usdc),
            directions = setOf(TxDirection.IN, TxDirection.OUT),
            amountAbove = BigDecimal("1.5"),
            amountBelow = BigDecimal("10"),
            sort = TxSort.AMOUNT,
            descending = false,
        )
        val q = query(filter)
        assertEquals(setOf(usdc), q.assets)
        assertEquals(setOf(TxDirection.IN, TxDirection.OUT), q.directions)
        assertEquals(BigDecimal("1.5"), q.amountAbove)
        assertEquals(BigDecimal("10"), q.amountBelow)
        assertEquals(TxSort.AMOUNT, q.sort)
        assertFalse(q.descending)
        assertEquals(networks, q.networks)
        assertTrue(q.accounts.isEmpty())
    }

    @Test
    fun aPeriodCombinedWithASearchKeepsBothAndTheSort() {
        val q = query(TxFilter(text = "rent", period = TxPeriod.LAST_30D, sort = TxSort.STATUS, descending = true))
        assertEquals("rent", q.text)
        assertEquals(now - 30 * day, q.fromMs)
        assertEquals(TxSort.STATUS, q.sort)
        assertTrue(q.descending)
    }

    @Test
    fun theActiveCountCountsFiltersButNotTheSearchOrTheSort() {
        assertEquals(0, TxFilter(text = "memo", sort = TxSort.FEE, descending = false).activeCount)
        val all = TxFilter(
            assets = setOf(usdc),
            period = TxPeriod.LAST_24H,
            directions = setOf(TxDirection.SELF),
            amountAbove = BigDecimal.ONE,
            amountBelow = BigDecimal.TEN,
        )
        assertEquals(5, all.activeCount)
    }
}
