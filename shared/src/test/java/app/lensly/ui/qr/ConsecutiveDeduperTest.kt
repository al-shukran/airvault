package app.lensly.ui.qr

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsecutiveDeduperTest {

    @Test
    fun dropsRepeatsInARowOnly() {
        val deduper = ConsecutiveDeduper()
        assertTrue(deduper.accept("A"))
        assertFalse(deduper.accept("A"))
        assertTrue(deduper.accept("B"))
        // A comes back after B, so it is a new run and passes again.
        assertTrue(deduper.accept("A"))
    }
}
