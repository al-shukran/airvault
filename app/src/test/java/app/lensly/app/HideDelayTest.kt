package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class HideDelayTest {

    @Test
    fun theDefaultIsOneHundredMillisecondsToOneSecond() {
        assertEquals(HideDelay(100L, 1_000L), HideDelay.DEFAULT)
    }

    @Test
    fun aRangeFromZeroToTheLimitIsAcceptedAtBothEdges() {
        assertEquals(HideDelay(0L, 0L), HideDelay.of(0L, 0L))
        assertEquals(HideDelay(0L, HideDelay.LIMIT_MS), HideDelay.of(0L, HideDelay.LIMIT_MS))
        assertEquals(HideDelay(HideDelay.LIMIT_MS, HideDelay.LIMIT_MS), HideDelay.of(HideDelay.LIMIT_MS, HideDelay.LIMIT_MS))
    }

    @Test
    fun aShortestAboveTheLongestIsRefused() {
        assertNull(HideDelay.of(600L, 500L))
    }

    @Test
    fun aLongestAboveTheLimitIsRefused() {
        assertNull(HideDelay.of(0L, HideDelay.LIMIT_MS + 1))
        assertNull(HideDelay.of(4_000L, 6_000L))
    }

    @Test
    fun aNegativeShortestIsRefused() {
        assertNull(HideDelay.of(-1L, 500L))
    }

    @Test
    fun aRangeOutsideTheLimitCannotBeBuilt() {
        assertFailsWith<IllegalArgumentException> { HideDelay(6_000L, 6_000L) }
        assertFailsWith<IllegalArgumentException> { HideDelay(600L, 500L) }
    }

    @Test
    fun theLimitIsFiveThousandMilliseconds() {
        assertEquals(5_000L, HideDelay.LIMIT_MS)
    }
}
