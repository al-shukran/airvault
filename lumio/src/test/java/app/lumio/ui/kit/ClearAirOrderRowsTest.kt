package app.lumio.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a dragged row is over, how the rows it passes make way, and where it settles. Three rows of 60, 60 and 80 lie at 0, 60 and 120. */
class ClearAirOrderRowsTest {

    private val tops = floatArrayOf(0f, 60f, 120f)
    private val heights = floatArrayOf(60f, 60f, 80f)

    @Test
    fun aRowStaysInItsSlotUntilItCrossesTheMiddleOfANeighbour() {
        assertEquals(0, hoverIndex(tops, heights, from = 0, offset = 59f))
    }

    @Test
    fun aRowDraggedDownPastTheMiddleOfTheRowBelowHoversOverIt() {
        assertEquals(1, hoverIndex(tops, heights, from = 0, offset = 61f))
    }

    @Test
    fun aRowDraggedUpPastTheMiddleOfTheRowAboveHoversOverIt() {
        assertEquals(0, hoverIndex(tops, heights, from = 1, offset = -61f))
    }

    @Test
    fun aRowDraggedPastTheEndHoversOverTheLastRow() {
        assertEquals(2, hoverIndex(tops, heights, from = 0, offset = 500f))
    }

    @Test
    fun aRowDraggedDownMakesTheRowsItPassesMoveUp() {
        val shifts = (0..2).map { shiftOf(it, from = 0, hover = 2, heights = heights) }
        assertEquals(listOf(0f, -60f, -60f), shifts)
    }

    @Test
    fun aRowDraggedUpMakesTheRowsItPassesMoveDown() {
        val shifts = (0..2).map { shiftOf(it, from = 2, hover = 1, heights = heights) }
        assertEquals(listOf(0f, 80f, 0f), shifts)
    }

    @Test
    fun aRowSettlesByTheHeightsOfTheRowsItPassed() {
        assertEquals(140f, slotOffset(heights, from = 0, slot = 2), 0f)
        assertEquals(-120f, slotOffset(heights, from = 2, slot = 0), 0f)
        assertEquals(0f, slotOffset(heights, from = 1, slot = 1), 0f)
    }
}
