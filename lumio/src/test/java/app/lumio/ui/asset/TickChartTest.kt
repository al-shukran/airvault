package app.lumio.ui.asset

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The tick chart's geometry: one tick per price after the first, spread from the left edge to the right one, each from the price before it to its own. */
class TickChartTest {
    @Test
    fun aSeriesOfOnePriceDrawsNoTick() {
        assertTrue(ticksOf(listOf(4.0), width = 200f, height = 100f).isEmpty())
    }

    @Test
    fun eachPriceAfterTheFirstMakesOneTickSpreadFromEdgeToEdge() {
        val ticks = ticksOf(listOf(1.0, 2.0, 3.0), width = 200f, height = 100f)

        assertEquals(listOf(0f, 200f), ticks.map { it.x })
    }

    @Test
    fun aTickRunsFromThePreviousPriceToItsOwnAndLowestSitsAtTheBottom() {
        val ticks = ticksOf(listOf(0.0, 10.0, 5.0), width = 200f, height = 100f)

        assertEquals(listOf(100f, 0f), ticks.map { it.from })
        assertEquals(listOf(0f, 50f), ticks.map { it.to })
    }
}
