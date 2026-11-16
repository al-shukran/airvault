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
    fun theSelectorOffersTheFourRangesInOrder() {
        assertEquals(listOf("24h", "7d", "30d", "1y"), ChartRange.entries.map { it.label })
    }

    @Test
    fun eachRangeWindowCoversItsSpan() {
        assertEquals(86_400_000L, ChartRange.H24.windowMs)
        assertEquals(604_800_000L, ChartRange.D7.windowMs)
        assertEquals(2_592_000_000L, ChartRange.D30.windowMs)
        assertEquals(31_536_000_000L, ChartRange.Y1.windowMs)
    }

    @Test
    fun theQueryStartsTheWindowAtItsFirstPointInUnixSeconds() {
        assertEquals(ChartQuery(startSec = 1_799_913_600L, span = 25, period = "1h"), ChartRange.H24.query(now))
        assertEquals(ChartQuery(startSec = 1_799_395_200L, span = 43, period = "4h"), ChartRange.D7.query(now))
        assertEquals(ChartQuery(startSec = 1_797_408_000L, span = 31, period = "1d"), ChartRange.D30.query(now))
        assertEquals(ChartQuery(startSec = 1_768_464_000L, span = 366, period = "1d"), ChartRange.Y1.query(now))
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
