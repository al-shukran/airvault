package app.lensly.core.parse

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class DecodeBudgetTest {

    @Test
    fun refusesTheItemThatGoesOverTheCount() {
        val budget = DecodeBudget("CBOR", maxItems = 3)
        assertNull(budget.charge())
        assertNull(budget.charge())
        assertNull(budget.charge())
        assertEquals("CBOR has more than 3 items", budget.charge())
    }

    @Test
    fun refusesOnceTheTimeBudgetIsSpent() {
        var now = 0L
        val budget = DecodeBudget("RLP", maxItems = 100, maxNanos = 25, nanoTime = { now })
        now = 20
        assertNull(budget.charge())
        now = 26
        assertEquals("RLP decoding ran past its time budget", budget.charge())
    }
}
