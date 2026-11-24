package app.lumio.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a dragged row lands: the row whose centre is nearest to where the drag has moved the dragged row's centre. */
class ClearAirOrderRowsTest {

    private val centres = listOf(10f, 70f, 130f)

    @Test
    fun aRowDraggedDownLandsOnTheRowBelow() {
        assertEquals(1, dropIndex(centres, from = 0, offset = 60f))
    }

    @Test
    fun aRowDraggedUpLandsOnTheRowAbove() {
        assertEquals(0, dropIndex(centres, from = 1, offset = -60f))
    }

    @Test
    fun aRowMovedLessThanHalfARowStaysWhereItIs() {
        assertEquals(1, dropIndex(centres, from = 1, offset = 4f))
    }

    @Test
    fun aRowDraggedPastTheEndLandsOnTheLastRow() {
        assertEquals(2, dropIndex(centres, from = 0, offset = 500f))
    }
}
