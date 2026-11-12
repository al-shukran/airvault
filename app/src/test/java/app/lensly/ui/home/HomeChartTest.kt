package app.lensly.ui.home

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import org.junit.Test

/** The chart's buckets: the days of each window, the status and network segments, the Other bucket, and the zone's day boundary. */
class HomeChartTest {

    private val utc = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2027, 1, 15)
    private val nowMs = at(today, hour = 12)

    private fun at(day: LocalDate, hour: Int): Long =
        ZonedDateTime.of(day, LocalTime.of(hour, 0), utc).toInstant().toEpochMilli()

    private fun daysAgo(days: Long, hour: Int = 12): Long = at(today.minusDays(days), hour)

    private fun record(
        atMs: Long,
        outcome: ReviewOutcome = ReviewOutcome.SIGNED,
        chainId: Long? = 1L,
        networkName: String? = "Ethereum",
    ): ReviewRecord = ReviewRecord(
        atMs = atMs,
        outcome = outcome,
        kind = ReviewKind.TRANSACTION,
        chainId = chainId,
        networkName = networkName,
        accountIndex = 0,
        address = null,
        function = null,
        to = null,
        amount = null,
        tokenContract = null,
        fee = emptyList(),
        params = emptyList(),
        origin = null,
        refusal = null,
        dataHash = "00",
    )

    @Test
    fun statusCountsEachDayOfTheWeekAndLeavesOutTheDayBefore() {
        val records = listOf(
            record(daysAgo(0, hour = 9)),
            record(daysAgo(0, hour = 15)),
            record(daysAgo(0, hour = 18), ReviewOutcome.REFUSED),
            record(daysAgo(1), ReviewOutcome.CANCELLED),
            record(daysAgo(6)),
            record(daysAgo(7)),
        )

        val chart = activityChart(records, nowMs, ChartWindow.WEEK, ChartBreakdown.STATUS, utc)

        assertEquals(7, chart.days.size)
        assertEquals(today.minusDays(6), chart.days.first().date)
        assertEquals(today, chart.days.last().date)
        // Signed, refused, cancelled, in that order.
        assertEquals(listOf(2, 1, 0), chart.days.last().counts)
        assertEquals(listOf(0, 0, 1), chart.days[5].counts)
        assertEquals(listOf(1, 0, 0), chart.days.first().counts)
        assertEquals(listOf("Signed", "Refused", "Cancelled"), chart.segments.map { it.label })
        assertEquals(listOf(3, 1, 1), chart.segments.map { it.count })
        assertEquals(5, chart.total)
    }

    @Test
    fun theMonthWindowKeepsTheLastThirtyDaysAndNoOlder() {
        val records = listOf(record(daysAgo(7)), record(daysAgo(29)), record(daysAgo(30)))

        val month = activityChart(records, nowMs, ChartWindow.MONTH, ChartBreakdown.STATUS, utc)
        val week = activityChart(records, nowMs, ChartWindow.WEEK, ChartBreakdown.STATUS, utc)

        assertEquals(30, month.days.size)
        assertEquals(today.minusDays(29), month.days.first().date)
        assertEquals(2, month.total)
        assertEquals(listOf(1, 0, 0), month.days[22].counts)
        assertEquals(listOf(1, 0, 0), month.days.first().counts)
        assertEquals(0, week.total)
    }

    @Test
    fun aRecordAfterTodayIsLeftOut() {
        val chart = activityChart(listOf(record(at(today.plusDays(1), hour = 9))), nowMs, ChartWindow.MONTH, ChartBreakdown.STATUS, utc)

        assertEquals(0, chart.total)
    }

    @Test
    fun appNamesTheThreeBusiestNetworksAndCountsTheRestAsOther() {
        val records = List(4) { record(daysAgo(0), chainId = 1L, networkName = "Ethereum") } +
            List(3) { record(daysAgo(0), chainId = 143L, networkName = "Monad") } +
            List(2) { record(daysAgo(0), chainId = 56L, networkName = "BNB Chain") } +
            // Base ties with BNB Chain and sorts after it, so it is the one that becomes Other.
            List(2) { record(daysAgo(1), chainId = 8453L, networkName = "Base") } +
            // A record with no chain is Other too.
            listOf(record(daysAgo(0), chainId = null, networkName = null))

        val chart = activityChart(records, nowMs, ChartWindow.MONTH, ChartBreakdown.APP, utc)

        assertEquals(listOf("Ethereum", "Monad", "BNB Chain", "Other"), chart.segments.map { it.label })
        assertEquals(listOf(4, 3, 2, 3), chart.segments.map { it.count })
        assertEquals(12, chart.total)
        // Today's Other is the chainless record; yesterday's is the Base pair.
        assertEquals(listOf(4, 3, 2, 1), chart.days.last().counts)
        assertEquals(listOf(0, 0, 0, 2), chart.days[28].counts)
    }

    @Test
    fun appLeavesOutTheOtherSegmentWhenNoRecordIsLeftForIt() {
        val records = listOf(record(daysAgo(0), chainId = 1L, networkName = "Ethereum"))

        val chart = activityChart(records, nowMs, ChartWindow.WEEK, ChartBreakdown.APP, utc)

        assertEquals(listOf("Ethereum"), chart.segments.map { it.label })
        assertEquals(1, chart.total)
    }

    @Test
    fun theZoneDecidesWhichDayARecordFallsOn() {
        // 20:00 UTC on the 14th is 05:00 on the 15th in Tokyo.
        val record = record(at(today.minusDays(1), hour = 20))

        val inUtc = activityChart(listOf(record), nowMs, ChartWindow.WEEK, ChartBreakdown.STATUS, utc)
        val inTokyo = activityChart(listOf(record), nowMs, ChartWindow.WEEK, ChartBreakdown.STATUS, ZoneId.of("Asia/Tokyo"))

        assertEquals(listOf(1, 0, 0), inUtc.days[5].counts)
        assertEquals(listOf(0, 0, 0), inUtc.days.last().counts)
        assertEquals(listOf(1, 0, 0), inTokyo.days.last().counts)
    }

    @Test
    fun noRecordsGiveEmptyDaysAndNoNetworks() {
        val status = activityChart(emptyList(), nowMs, ChartWindow.WEEK, ChartBreakdown.STATUS, utc)
        val app = activityChart(emptyList(), nowMs, ChartWindow.MONTH, ChartBreakdown.APP, utc)

        assertEquals(7, status.days.size)
        assertEquals(0, status.total)
        assertEquals(3, status.segments.size)
        assertEquals(0, app.total)
        assertEquals(emptyList<ChartSegment>(), app.segments)
    }
}
