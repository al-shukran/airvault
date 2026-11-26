package app.lumio

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The footnote under the watchlist: today's prices give their time, and older ones their day too, so a kept price never reads as today's. */
class HomeFootnoteTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun todaysPricesGiveTheirTime() {
        assertEquals("Prices at 12:30, change over 24 hours.", footnoteOf(at(26, 12, 30), nowMs = at(26, 18, 0), zone = zone))
    }

    @Test
    fun olderPricesAlsoGiveTheirDay() {
        assertEquals("Prices at 23:50 on 25 Nov, change over 24 hours.", footnoteOf(at(25, 23, 50), nowMs = at(26, 0, 10), zone = zone))
    }

    @Test
    fun noPriceReadMeansNoFootnote() {
        assertNull(footnoteOf(null, nowMs = at(26, 12, 0), zone = zone))
    }
}
