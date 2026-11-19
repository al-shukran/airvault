package app.lensly.ui.components

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The strip's cell under a finger, and where the strip sits, with no layout behind them. */
class KeyAlternatesTest {

    @Test
    fun theFingerChoosesTheCellItIsOver() {
        // A strip of three 40 px cells starting at x = 100.
        assertEquals(0, alternateIndex(100f, left = 100f, cell = 40f, count = 3))
        assertEquals(0, alternateIndex(139f, left = 100f, cell = 40f, count = 3))
        assertEquals(1, alternateIndex(140f, left = 100f, cell = 40f, count = 3))
        assertEquals(2, alternateIndex(219f, left = 100f, cell = 40f, count = 3))
    }

    @Test
    fun aFingerBeyondTheStripChoosesTheNearestEnd() {
        assertEquals(0, alternateIndex(20f, left = 100f, cell = 40f, count = 3))
        assertEquals(2, alternateIndex(400f, left = 100f, cell = 40f, count = 3))
    }

    @Test
    fun theStripIsCentredOnTheKeyAndKeptOnScreen() {
        assertEquals(100f, alternateStripLeft(centre = 150f, width = 100f, containerWidth = 300f))
        assertEquals(0f, alternateStripLeft(centre = 20f, width = 100f, containerWidth = 300f))
        assertEquals(200f, alternateStripLeft(centre = 290f, width = 100f, containerWidth = 300f))
    }

    @Test
    fun lettersHaveAccentsAndDigitsHaveNone() {
        assertTrue('é' in ALTERNATES.getValue('e'))
        assertTrue('è' in ALTERNATES.getValue('e'))
        assertNull(ALTERNATES['1'])
        assertNull(ALTERNATES[' '])
    }
}
