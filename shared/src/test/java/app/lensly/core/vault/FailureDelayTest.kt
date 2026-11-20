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
        assertEquals(0L, FailureDelay(created).delayFor(0, learningWeek = false))
    }

    @Test
    fun everyFailureCostsTheLearningCapWhileTheLearningWeekRuns() {
        val delay = FailureDelay(created)
        assertEquals(10_000L, delay.delayFor(1, learningWeek = true))
        assertEquals(10_000L, delay.delayFor(2, learningWeek = true))
        assertEquals(10_000L, delay.delayFor(9, learningWeek = true))
    }

    @Test
    fun doublesFromTheBaseThenStopsAtTheDefaultCap() {
        val delay = FailureDelay(created)
        assertEquals(10_000L, delay.delayFor(1, learningWeek = false))
        assertEquals(20_000L, delay.delayFor(2, learningWeek = false))
        assertEquals(40_000L, delay.delayFor(3, learningWeek = false))
        assertEquals(60_000L, delay.delayFor(4, learningWeek = false))
        assertEquals(60_000L, delay.delayFor(50, learningWeek = false))
    }

    @Test
    fun hugeFailureCountsDoNotOverflow() {
        assertEquals(60_000L, FailureDelay(created).delayFor(Int.MAX_VALUE, learningWeek = false))
        assertEquals(10_000L, FailureDelay(created, configuredCapMs = 10_000L).delayFor(Int.MAX_VALUE, learningWeek = false))
    }

    @Test
    fun configuredCapBoundsBothPeriods() {
        val tenSecondCap = FailureDelay(created, configuredCapMs = 10_000L)
        assertEquals(10_000L, tenSecondCap.delayFor(1, learningWeek = true))
        assertEquals(10_000L, tenSecondCap.delayFor(5, learningWeek = false))

        val oneHourCap = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        assertEquals(FailureDelay.LEARNING_CAP_MS, oneHourCap.capMs(learningWeek = true))
        assertEquals(FailureDelay.MAX_CAP_MS, oneHourCap.capMs(learningWeek = false))
        assertEquals(10_000L, oneHourCap.delayFor(1, learningWeek = false))
        // 10 s * 2^9 = 5120 s, which is above the one-hour cap.
        assertEquals(3_600_000L, oneHourCap.delayFor(10, learningWeek = false))
    }

    @Test
    fun rejectsCapsOutsideTenSecondsToOneHour() {
        assertFailsWith<IllegalArgumentException> { FailureDelay(created, configuredCapMs = 9_999L) }
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
    fun rebootWithoutABootCountIsNotShortenedByAForwardWallClockJump() {
        // Master credited the 15 s of wall time here, which ended the 10 s wait. A reboot now credits no more than the time since
        // boot (2 s), so the forward jump cannot shorten the wait.
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)

        // After a reboot the monotonic clock restarts near zero, while 15 s of wall time have passed.
        clock.elapsed = 2_000L
        clock.wall += 15_000L
        assertEquals(2_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertTrue(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun aForwardWallClockJumpAcrossARebootCannotCreditMoreThanTheServedTimeAndTheBootTime() {
        // 4 s were served before the save, and the state has no boot count. After the reboot 1 s has passed, and the wall clock jumps an hour.
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed - 4_000L,
            elapsedAtSave = 4_000L,
        )

        clock.elapsed = 1_000L
        clock.wall += FailureDelay.MAX_CAP_MS
        assertEquals(5_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertTrue(FailureDelay(created).isBlocked(state, clock))
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
    fun aRebootSeenByTheBootCountContinuesTheWaitEvenWhenTheMonotonicClockPassedTheFailure() {
        val clock = FakeClock(wall = afterLearning, elapsed = 1_000L)
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            bootCount = 7,
            elapsedAtSave = 1_000L,
        )

        // The device rebooted. The monotonic clock is back at 1.5 s, below the 2 s it read when the state was saved, and the wall
        // clock went back an hour. The wait continues: 1 s before the reboot and 1.5 s after it.
        clock.elapsed = 1_500L
        clock.wall -= 3_600_000L
        assertEquals(2_500L, FailureDelay.elapsedSinceFailure(state, clock, bootCount = 8))
        assertTrue(FailureDelay(created).isBlocked(state, clock, bootCount = 8))
    }

    @Test
    fun aBootCountRaisedWithoutARebootCannotOverstateTheServedTime() {
        // The attacker writes BOOT_COUNT up by one, and the device has not rebooted. Master added the monotonic reading to the
        // saved time, which read as 901 s of a 10 s wait. The monotonic clock did not restart, so the count is not believed.
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed, bootCount = 7)
        clock.advance(1_000)
        assertEquals(1_000L, FailureDelay.elapsedSinceFailure(state, clock, bootCount = 8))
        assertTrue(FailureDelay(created).isBlocked(state, clock, bootCount = 8))

        // With 5 s already served before the save, the same write adds only the monotonic time since that save.
        val saved = FailureState(
            seq = 3,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed - 5_000L,
            bootCount = 7,
            elapsedAtSave = 5_000L,
        )
        clock.advance(1_000)
        assertEquals(6_000L, FailureDelay.elapsedSinceFailure(saved, clock, bootCount = 8))
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
    fun withoutACurrentBootCountAForwardWallClockJumpStillCannotShortenTheWait() {
        val clock = FakeClock(wall = afterLearning, elapsed = 900_000L)
        val state = FailureState(
            seq = 2,
            failures = 1,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
            bootCount = 7,
        )

        // The state names a boot, but the device cannot report the current one. The monotonic clock going back shows the reboot, and
        // the time since boot (2 s) is all that is credited, so the 15 s jump cannot end the 10 s wait.
        clock.elapsed = 2_000L
        clock.wall += 15_000L
        assertEquals(2_000L, FailureDelay.elapsedSinceFailure(state, clock))
        assertTrue(FailureDelay(created).isBlocked(state, clock))
    }

    @Test
    fun wallClockMovedBackwardGivesNegativeElapsed() {
        val clock = FakeClock()
        val state = FailureState(seq = 2, failures = 1, lastFailureWallMs = clock.wall, lastFailureElapsedMs = clock.elapsed)
        clock.wall -= 1
        assertTrue(FailureDelay.elapsedSinceFailure(state, clock) < 0)
    }

    @Test
    fun theLearningWeekIsSevenDaysOfTimeSinceCreationInTheSameBoot() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        val state = learningState(createdElapsedMs = 0L, createdBootCount = 7)
        val clock = FakeClock(wall = created, elapsed = FailureDelay.LEARNING_PERIOD_MS - 1)
        // Four failures under the owner's one-hour cap wait 80 s. The learning cap waits 10 s.
        assertEquals(10_000L, delay.waitFor(state, clock, bootCount = 7))
        clock.elapsed = FailureDelay.LEARNING_PERIOD_MS
        assertEquals(80_000L, delay.waitFor(state, clock, bootCount = 7))
    }

    @Test
    fun aBootCountThatDiffersOrIsUnreadableEndsTheLearningWeek() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        val state = learningState(createdElapsedMs = 0L, createdBootCount = 7)
        val clock = FakeClock(wall = created, elapsed = 1_000L)
        assertEquals(10_000L, delay.waitFor(state, clock, bootCount = 7))
        assertEquals(80_000L, delay.waitFor(state, clock, bootCount = 8))
        assertEquals(80_000L, delay.waitFor(state, clock, bootCount = null))
    }

    @Test
    fun aVaultWithoutItsCreationReadingsNeverGetsTheLearningCap() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        val clock = FakeClock(wall = created, elapsed = 1_000L)
        assertEquals(80_000L, delay.waitFor(learningState(createdElapsedMs = null, createdBootCount = 7), clock, bootCount = 7))
        assertEquals(80_000L, delay.waitFor(learningState(createdElapsedMs = 0L, createdBootCount = null), clock, bootCount = null))
    }

    @Test
    fun aClockBeforeTheVaultWasMadeIsPastTheLearningWeek() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        val state = learningState(createdElapsedMs = 0L, createdBootCount = 7)
        val clock = FakeClock(wall = created - 1, elapsed = 1_000L)
        assertEquals(80_000L, delay.waitFor(state, clock, bootCount = 7))
    }

    @Test
    fun aWaitRestartedByAClockAnomalyIsTheFullCapWhateverTheCount() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MAX_CAP_MS)
        val clock = FakeClock(wall = created, elapsed = 1_000L)
        val anomaly = learningState(createdElapsedMs = 0L, createdBootCount = 7).copy(fullCapWait = true)
        assertEquals(FailureDelay.MAX_CAP_MS, delay.waitFor(anomaly.copy(failures = 1), clock, bootCount = 7))
        // A lockout before any wrong try still waits the full cap, as a tampered copy always has.
        assertEquals(FailureDelay.MAX_CAP_MS, delay.waitFor(anomaly.copy(failures = 0), clock, bootCount = 7))
    }

    @Test
    fun ownerCapFromTheStateAppliesToEveryWait() {
        val capped = stateWithCap(10_000L, failures = 3)
        val delay = FailureDelay(created, FailureDelay.configuredCapFor(capped))
        // The spec delay after three failures is 40 s, and the owner's 10 s cap is the smaller one.
        assertEquals(10_000L, delay.delayFor(capped.failures, learningWeek = false))
        assertEquals(10_000L, delay.delayFor(1, learningWeek = true))
    }

    @Test
    fun theFloorCapHoldsAfterTheLearningPeriod() {
        val delay = FailureDelay(created, configuredCapMs = FailureDelay.MIN_CAP_MS)
        assertEquals(10_000L, delay.delayFor(1, learningWeek = true))
        assertEquals(10_000L, delay.delayFor(1, learningWeek = false))
        assertEquals(10_000L, delay.delayFor(9, learningWeek = false))
    }

    @Test
    fun defaultCapIsTheSpecDefault() {
        val default = stateWithCap(null, failures = 1)
        assertEquals(FailureDelay.DEFAULT_CAP_MS, FailureDelay.configuredCapFor(default))
        val delay = FailureDelay(created, FailureDelay.configuredCapFor(default))
        assertEquals(10_000L, delay.delayFor(1, learningWeek = true))
        assertEquals(60_000L, delay.delayFor(4, learningWeek = false))
    }

    private fun learningState(createdElapsedMs: Long?, createdBootCount: Int?) = FailureState(
        seq = 2,
        failures = 4,
        lastFailureWallMs = created,
        lastFailureElapsedMs = 0L,
        createdElapsedMs = createdElapsedMs,
        createdBootCount = createdBootCount,
    )

    private fun stateWithCap(capMs: Long?, failures: Int) = FailureState(
        seq = 2,
        failures = failures,
        lastFailureWallMs = created,
        lastFailureElapsedMs = 0L,
        capMs = capMs,
    )
}
