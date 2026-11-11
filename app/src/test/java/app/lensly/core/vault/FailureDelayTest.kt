package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class FailureDelayTest {
    private val created = FakeClock.START_WALL
    private val afterLearning = created + FailureDelay.LEARNING_PERIOD_MS

    @Test
    fun noFailuresMeansNoDelay() {
        assertEquals(0L, FailureDelay(created).delayFor(0, afterLearning))
    }

    @Test
    fun everyFailureCostsTheLearningCapDuringTheFirstSevenDays() {
        val delay = FailureDelay(created)
        assertEquals(10_000L, delay.delayFor(1, created))
        assertEquals(10_000L, delay.delayFor(2, created))
        assertEquals(10_000L, delay.delayFor(9, created + 1))
    }

    @Test
    fun doublesFromTheBaseThenStopsAtTheDefaultCap() {
        val delay = FailureDelay(created)
        assertEquals(10_000L, delay.delayFor(1, afterLearning))
        assertEquals(20_000L, delay.delayFor(2, afterLearning))
        assertEquals(40_000L, delay.delayFor(3, afterLearning))
        assertEquals(60_000L, delay.delayFor(4, afterLearning))
        assertEquals(60_000L, delay.delayFor(50, afterLearning))
    }

    @Test
    fun hugeFailureCountsDoNotOverflow() {
        assertEquals(60_000L, FailureDelay(created).delayFor(Int.MAX_VALUE, afterLearning))
        assertEquals(1_000L, FailureDelay(created, configuredCapMs = 1_000L).delayFor(Int.MAX_VALUE, created))
    }

    @Test
    fun learningPeriodEndsExactlyAtSevenDays() {
        val delay = FailureDelay(created)
        assertEquals(FailureDelay.LEARNING_CAP_MS, delay.capMs(afterLearning - 1))
        assertEquals(FailureDelay.DEFAULT_CAP_MS, delay.capMs(afterLearning))
    }

    @Test
    fun configuredCapBoundsBothPeriods() {
        val oneSecondCap = FailureDelay(created, configuredCapMs = 1_000L)
        assertEquals(1_000L, oneSecondCap.delayFor(1, created))
        assertEquals(1_000L, oneSecondCap.delayFor(5, afterLearning))

        val oneHourCap = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        assertEquals(10_000L, oneHourCap.delayFor(1, afterLearning))
        // 10 s * 2^9 = 5120 s, which is above the one-hour cap.
        assertEquals(3_600_000L, oneHourCap.delayFor(10, afterLearning))
    }

    @Test
    fun rejectsCapsOutsideOneSecondToOneHour() {
        assertFailsWith<IllegalArgumentException> { FailureDelay(created, configuredCapMs = 999L) }
        assertFailsWith<IllegalArgumentException> { FailureDelay(created, configuredCapMs = 3_600_001L) }
    }

    @Test
    fun blocksUntilTheDelayHasRunOut() {
        val delay = FailureDelay(created)
        val clock = FakeClock(wall = afterLearning, elapsed = 1_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)

        clock.advance(9_999)
        assertTrue(delay.isBlocked(state, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(state, clock))
    }

    @Test
    fun noFailuresNeverBlocks() {
        val clock = FakeClock(wall = afterLearning)
        val state = FailureState(seq = 1, failures = 0, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)
        assertFalse(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun forwardWallClockJumpDoesNotShortenTheDelayWithinOneBoot() {
        val clock = FakeClock()
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)
        clock.advance(1_000)
        clock.wall += 3_600_000L
        assertEquals(1_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertTrue(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun rebootFallsBackToTheWallClock() {
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)

        // After a reboot the monotonic clock restarts near zero, while 15 s of wall time have passed.
        clock.elapsed = 2_000L
        clock.wall += 15_000L
        assertEquals(15_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertFalse(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun aRebootTheMonotonicClockDoesNotShowIsMeasuredLikeTheSameBoot() {
        // The device stayed up after the reboot for longer than the failure had been up before it, so the monotonic clock did not go
        // back. The wait is then measured as the same boot: the monotonic time since the failure, and never more than that.
        val clock = FakeClock(wall = afterLearning, elapsed = 1_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)

        clock.elapsed = 5_000L
        clock.wall += 3_600_000L
        assertEquals(4_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertTrue(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun aWallClockMovedBackwardsAcrossARebootWithoutABootCountReadsAsTampering() {
        // Master's behaviour, kept for a device that reports no boot count: only the monotonic clock can show the reboot, and
        // the wall clock then carries the wait, so a clock that went back is negative and FailureStore treats it as tampering.
        // The recorded boot count is what tells a reboot apart from a moved clock.
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)

        clock.elapsed = 2_000L
        clock.wall -= 3_600_000L
        assertTrue(FailureDelay.elapsedSinceFailure(state, clock) < 0)
    }

    @Test
    fun aBootCountThatWentUpContinuesTheWaitEvenWhenTheMonotonicClockPassedTheFailure() {
        val clock = FakeClock(wall = afterLearning, elapsed = 1_000L)
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            bootCount = 7,
            elapsedAtSave = 1_000L,
        )

        // The device rebooted, stayed up longer than the failure had been up before it, and the wall clock went back an hour.
        // The wait continues: 1 s before the reboot and 2 s after it.
        clock.elapsed = 2_000L
        clock.wall -= 3_600_000L
        assertEquals(3_000L, FailureDelay.elapsedSinceFailure(state, clock, bootCount = 8))
        assertTrue(FailureDelay(created).isBlocked(state, clock, bootCount = 8))
    }

    @Test
    fun theSameBootCountKeepsTheSmallerReading() {
        val clock = FakeClock()
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            bootCount = 7,
        )

        clock.advance(1_000)
        clock.wall += 3_600_000L
        assertEquals(1_000L, FailureDelay.elapsedSinceFailure(state, clock, bootCount = 7))
    }

    @Test
    fun withoutACurrentBootCountTheWallClockCarriesTheWait() {
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            bootCount = 7,
        )

        // The state names a boot, but the device cannot report the current one, so the monotonic clock going back is the only
        // reboot signal and the wall clock carries the wait, as on master.
        clock.elapsed = 2_000L
        clock.wall += 15_000L
        assertEquals(15_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertFalse(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun wallClockMovedBackwardGivesNegativeElapsed() {
        val clock = FakeClock()
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)
        clock.wall -= 1
        assertTrue(FailureDelay.elapsedSinceFailure(state, clock) < 0)
    }

    @Test
    fun ownerCapFromTheStateAppliesToEveryWait() {
        val capped = stateWithCap(5_000L, failures = 3)
        val delay = FailureDelay(created, FailureDelay.configuredCapFor(capped))
        // The spec delay after three failures is 40 s, and the owner's 5 s cap is the smaller one.
        assertEquals(5_000L, delay.delayFor(capped.failures, afterLearning))
        assertEquals(5_000L, delay.delayFor(1, created))
    }

    @Test
    fun capBelowTenSecondsAlsoLimitsTheLearningPeriod() {
        val delay = FailureDelay(created, configuredCapMs = 5_000L)
        assertEquals(5_000L, delay.delayFor(1, created))
        assertEquals(5_000L, delay.delayFor(1, afterLearning))
        assertEquals(5_000L, delay.delayFor(9, afterLearning))
    }

    @Test
    fun defaultCapIsTheSpecDefault() {
        val default = stateWithCap(null, failures = 1)
        assertEquals(FailureDelay.DEFAULT_CAP_MS, FailureDelay.configuredCapFor(default))
        val delay = FailureDelay(created, FailureDelay.configuredCapFor(default))
        assertEquals(10_000L, delay.delayFor(1, created))
        assertEquals(60_000L, delay.delayFor(4, afterLearning))
    }

    @Test
    fun warningIsShownOnlyForCapsBelowTenSeconds() {
        assertTrue(FailureDelay.warnsAt(1_000L))
        assertTrue(FailureDelay.warnsAt(9_999L))
        assertFalse(FailureDelay.warnsAt(10_000L))
        assertFalse(FailureDelay.warnsAt(3_600_000L))
        assertFalse(FailureDelay.warnsAt(null))
    }

    private fun stateWithCap(capMs: Long?, failures: Int) = FailureState(
        seq = 2,
        failures = failures,
        lastFailureWallMs = created,
        lastFailureElapsedMs = 0L,
        capMs = capMs,
    )
}
