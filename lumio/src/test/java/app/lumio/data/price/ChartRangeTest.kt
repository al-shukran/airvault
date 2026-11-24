package app.lumio.data.price

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The range selector's spans, and the query each span sends: the window's start, the number of points, and their spacing. */
class ChartRangeTest {
    /** A fixed time in epoch milliseconds: 1_800_000_000 seconds. */
    private val now = 1_800_000_000_000L

    @Test
    fun theSelectorOffersTheEightRangesInOrderWithTheFiveMinuteRangeFirst() {
        assertEquals(listOf("5m", "15m", "1h", "4h", "12h", "1d", "7d", "1m"), ChartRange.entries.map { it.label })
    }

    @Test
    fun theFiveMinuteRangeIsTwoPointsFiveMinutesApart() {
        val fiveMinutes = ChartRange.entries.first { it.label == "5m" }
        assertEquals(ChartQuery(startSec = 1_799_999_700L, span = 2, period = "5m"), fiveMinutes.query(now))
        assertEquals(300_000L, fiveMinutes.windowMs)
    }

    @Test
    fun theSubHourRangesUseThePeriodsTheSourceServes() {
        assertEquals(ChartQuery(startSec = 1_799_999_100L, span = 4, period = "5m"), ChartRange.M15.query(now))
        assertEquals(ChartQuery(startSec = 1_799_996_400L, span = 13, period = "5m"), ChartRange.H1.query(now))
        assertEquals(ChartQuery(startSec = 1_799_985_600L, span = 17, period = "15m"), ChartRange.H4.query(now))
    }

    @Test
    fun eachRangeWindowCoversItsLabel() {
        assertEquals(900_000L, ChartRange.M15.windowMs)
        assertEquals(3_600_000L, ChartRange.H1.windowMs)
        assertEquals(14_400_000L, ChartRange.H4.windowMs)
        assertEquals(43_200_000L, ChartRange.H12.windowMs)
        assertEquals(86_400_000L, ChartRange.D1.windowMs)
        assertEquals(604_800_000L, ChartRange.D7.windowMs)
        assertEquals(2_592_000_000L, ChartRange.D30.windowMs)
    }

    @Test
    fun theQueryStartsTheWindowAtItsFirstPointInUnixSeconds() {
        assertEquals(ChartQuery(startSec = 1_799_913_600L, span = 25, period = "1h"), ChartRange.D1.query(now))
        assertEquals(ChartQuery(startSec = 1_799_395_200L, span = 43, period = "4h"), ChartRange.D7.query(now))
        assertEquals(ChartQuery(startSec = 1_797_408_000L, span = 31, period = "1d"), ChartRange.D30.query(now))
    }

    @Test
    fun theLastPointOfEachQueryIsTheTimeAsked() {
        for (range in ChartRange.entries) {
            val query = range.query(now)
            val periodSec = range.periodMs / 1_000L
            assertEquals(now / 1_000L, query.startSec + (query.span - 1) * periodSec, "range ${range.label}")
        }
    }

    @Test
    fun aSeriesReportsItsLowHighAndDirection() {
        val rising = PriceSeries(listOf(PricePoint(1_000L, 2.0), PricePoint(2_000L, 5.0), PricePoint(3_000L, 3.0)))
        assertEquals(2.0, rising.low)
        assertEquals(5.0, rising.high)
        assertTrue(rising.rising)

        val falling = PriceSeries(listOf(PricePoint(1_000L, 4.0), PricePoint(2_000L, 1.0)))
        assertFalse(falling.rising)
    }

    @Test
    fun anEmptySeriesHasNoLowHighAndCountsAsRising() {
        val empty = PriceSeries(emptyList())
        assertNull(empty.low)
        assertNull(empty.high)
        assertTrue(empty.rising)
    }
}
