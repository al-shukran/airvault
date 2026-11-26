package app.lumio.ui.home

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The words and the chart of a watchlist row: the change's arrow and tone, the ticker line, and the ticks with their 2% floor. */
class MarketFormatTest {
    @Test
    fun agoCountsUpInSecondsMinutesHoursAndDays() {
        assertEquals("8 s ago", agoText(8_000L))
        assertEquals("3 min ago", agoText(180_000L))
        assertEquals("3 h ago", agoText(3 * 3_600_000L))
        assertEquals("2 d ago", agoText(2 * 86_400_000L))
        assertEquals("0 s ago", agoText(-5_000L))
    }

    @Test
    fun aRiseIsGreenWithAnUpArrow() {
        assertEquals(ChangeTone.RISE, changeTone(3.36))
        assertEquals("▲ 3.36%", changeText(3.36))
    }

    @Test
    fun aFallIsRedWithADownArrow() {
        assertEquals(ChangeTone.FALL, changeTone(-2.41))
        assertEquals("▼ 2.41%", changeText(-2.41))
    }

    @Test
    fun noChangeIsGreyWithNoArrow() {
        assertEquals(ChangeTone.FLAT, changeTone(0.0))
        assertEquals("0.00%", changeText(0.0))
        // A change too small to show at two places is no change: no arrow, and never "▼ 0.00%".
        assertEquals(ChangeTone.FLAT, changeTone(-0.004))
        assertEquals("0.00%", changeText(-0.004))
    }

    @Test
    fun theTickerLineIsLeftOutWhenItIsTheName() {
        assertNull(tickerLine(name = "BNB", ticker = "BNB"))
        assertEquals("BTC", tickerLine(name = "Bitcoin", ticker = "BTC"))
        assertNull(tickerLine(name = "Bitcoin", ticker = ""))
    }

    @Test
    fun fewerThanTwoValuesDrawNoTicks() {
        assertTrue(ticksOf(emptyList(), width = 56f, height = 22f).isEmpty())
        assertTrue(ticksOf(listOf(1.0), width = 56f, height = 22f).isEmpty())
    }

    @Test
    fun aDayThatMovesFillsTheChartHeight() {
        val ticks = ticksOf(listOf(100.0, 110.0, 105.0), width = 56f, height = 22f)
        assertEquals(2, ticks.size)
        assertEquals(2f, ticks.minOf { it.top }, 0.01f)
        assertEquals(20f, ticks.maxOf { it.bottom }, 0.01f)
        // The ink runs from 1 dp to 46.8 dp of the 56 dp slot, as the mock's does.
        assertEquals(1f, ticks.first().x, 0.01f)
        assertEquals(46.8f, ticks.last().x, 0.01f)
    }

    @Test
    fun aStablecoinsNoiseIsDrawnOnTheTwoPercentFloorAndReadsFlat() {
        val ticks = ticksOf(listOf(0.9990, 0.9992, 0.9989, 0.9991), width = 56f, height = 22f)
        // 0.03% of noise spans under 1/60 of the height around the middle line, so it reads flat.
        ticks.forEach { tick ->
            assertTrue(tick.top > 10f && tick.bottom < 12f, "tick $tick is off the middle line")
        }
    }

    @Test
    fun aDayOfManyPointsIsDrawnAsTwentyTwoTicksOverTheSameSpan() {
        val ticks = ticksOf(List(25) { 100.0 + it }, width = 56f, height = 22f)
        assertEquals(22, ticks.size)
        assertEquals(1f, ticks.first().x, 0.01f)
        assertEquals(46.8f, ticks.last().x, 0.01f)
        // The first and the last points are kept: the ink still runs from the bottom to the top, give or take half a short tick, as the mock's 20.4 dp is.
        assertEquals(2f, ticks.minOf { it.top }, 0.5f)
        assertEquals(20f, ticks.maxOf { it.bottom }, 0.5f)
    }

    @Test
    fun aFlatDaySitsOnTheMiddleLine() {
        val ticks = ticksOf(listOf(1.0, 1.0, 1.0), width = 56f, height = 22f)
        ticks.forEach { tick ->
            assertEquals(10.2f, tick.top, 0.01f)
            assertEquals(11.8f, tick.bottom, 0.01f)
        }
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) = org.junit.Assert.assertEquals(expected, actual, delta)
}
