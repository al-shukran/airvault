package app.lumio.data.price

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The chart time frames the selector offers, and the query each one sends: the window's start, the number of points, and their interval. */
class ChartFrameTest {
    /** A fixed time in epoch milliseconds: 1_800_000_000 seconds. */
    private val now = 1_800_000_000_000L

    @Test
    fun theSelectorOffersFiveFramesInOrderWithTheFiveMinuteFrameFirst() {
        assertEquals(listOf("5m", "15m", "1h", "4h", "1d"), ChartFrame.entries.map { it.label })
    }

    @Test
    fun eachFrameLabelIsTheIntervalOfOnePoint() {
        assertEquals(listOf(300_000L, 900_000L, 3_600_000L, 14_400_000L, 86_400_000L), ChartFrame.entries.map { it.periodMs })
    }

    @Test
    fun eachFrameQueryStartsItsWindowAtItsFirstPointInUnixSeconds() {
        assertEquals(ChartQuery(startSec = 1_799_985_900L, span = 48, period = "5m"), ChartFrame.M5.query(now))
        assertEquals(ChartQuery(startSec = 1_799_957_700L, span = 48, period = "15m"), ChartFrame.M15.query(now))
        assertEquals(ChartQuery(startSec = 1_799_917_200L, span = 24, period = "1h"), ChartFrame.H1.query(now))
        assertEquals(ChartQuery(startSec = 1_799_323_200L, span = 48, period = "4h"), ChartFrame.H4.query(now))
        assertEquals(ChartQuery(startSec = 1_795_939_200L, span = 48, period = "1d"), ChartFrame.D1.query(now))
    }

    @Test
    fun eachFrameWindowRunsFromItsFirstPointToItsLast() {
        assertEquals(14_100_000L, ChartFrame.M5.windowMs)
        assertEquals(42_300_000L, ChartFrame.M15.windowMs)
        assertEquals(82_800_000L, ChartFrame.H1.windowMs)
        assertEquals(676_800_000L, ChartFrame.H4.windowMs)
        assertEquals(4_060_800_000L, ChartFrame.D1.windowMs)
    }

    @Test
    fun theLastPointOfEachQueryIsTheTimeAsked() {
        for (frame in ChartFrame.entries) {
            val query = frame.query(now)
            val periodSec = frame.periodMs / 1_000L
            assertEquals(now / 1_000L, query.startSec + (query.span - 1) * periodSec, "frame ${frame.label}")
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
