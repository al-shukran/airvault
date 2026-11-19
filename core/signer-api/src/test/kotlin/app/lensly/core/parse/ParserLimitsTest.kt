package app.lensly.core.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParserLimitsTest {

    /** An app asking for larger limits than the core's gets the core's, so an app can lower a limit and never raise one. */
    @Test
    fun anAppCannotRaiseACoreLimit() {
        val raised = ParserLimits(maxSignDataBytes = Int.MAX_VALUE, maxPayloadBytes = Int.MAX_VALUE)
        assertEquals(ParserLimits(), ParserLimits().lowerTo(raised))
    }

    /** An app lowers each limit to its own value, where that is lower than the core's. */
    @Test
    fun anAppLowersEachLimitToItsOwnValue() {
        val app = ParserLimits(maxSignDataBytes = 100, maxPayloadBytes = 200)
        assertEquals(app, ParserLimits().lowerTo(app))
    }

    /** A limit that is already lower than the app's stays lower. */
    @Test
    fun aLimitBelowTheAppsStaysBelow() {
        val lowered = ParserLimits(maxSignDataBytes = 10, maxPayloadBytes = 20)
        assertEquals(lowered, lowered.lowerTo(ParserLimits()))
    }

    @Test
    fun aDecodeBudgetRefusesTheItemAfterItsLimit() {
        val budget = DecodeBudget("CBOR", maxItems = 2, nanoTime = { 0L })
        assertNull(budget.charge())
        assertNull(budget.charge())
        assertEquals("CBOR has more than 2 items", budget.charge())
    }

    @Test
    fun aDecodeBudgetRefusesOnceItsTimeIsSpent() {
        var now = 0L
        val budget = DecodeBudget("CBOR", maxItems = 10, maxNanos = 100L, nanoTime = { now })
        assertNull(budget.charge())
        now = 101L
        assertEquals("CBOR decoding ran past its time budget", budget.charge())
    }
}
