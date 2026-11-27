package app.lensly.ui.navigation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The lock rules of the cover, as pure functions of the policy and of the clock. Lumio's policy is the one the owner can set: an
 * idle timeout (thirty minutes by default), a switch that locks when the app is switched away from (off by default), and launches
 * that the app makes itself, which never lock. Lensly's policy is the one it has always had, and these rules keep it unchanged.
 *
 * The rules live in :shared, and this module runs them with the cover's other navigation tests.
 */
class LockRulesTest {

    private val minute = 60_000L

    /** Lumio with the switch off and the default timeout: the policy a fresh install runs. */
    private val lumio = LockPolicy(idleTimeoutMs = 30 * minute, lockWhenBackgrounded = false, ownLaunchesKeepUnlocked = true)

    /** Lumio with the switch on. */
    private val lumioSwitch = lumio.copy(lockWhenBackgrounded = true)

    @Test
    fun lensly_locksOnEveryStop() {
        assertTrue(LockRules.lockOnStop(LockPolicy.LENSLY, causedByOwnLaunch = false))
    }

    @Test
    fun lensly_hasItsTwoMinuteIdleTimeoutAndRunsItsCountdownThroughALaunch() {
        assertEquals(2 * minute, LockPolicy.LENSLY.idleTimeoutMs)
        assertFalse(LockPolicy.LENSLY.ownLaunchesKeepUnlocked)
        assertFalse(LockRules.idlePaused(LockPolicy.LENSLY, launchOpen = true))
    }

    @Test
    fun lumioWithTheSwitchOff_doesNotLockOnAStop() {
        assertFalse(LockRules.lockOnStop(lumio, causedByOwnLaunch = false))
    }

    @Test
    fun lumioWithTheSwitchOn_locksOnAStop() {
        assertTrue(LockRules.lockOnStop(lumioSwitch, causedByOwnLaunch = false))
    }

    @Test
    fun aStopThatAnOwnLaunchCauses_neverLocks() {
        assertFalse(LockRules.lockOnStop(LockPolicy.LENSLY, causedByOwnLaunch = true))
        assertFalse(LockRules.lockOnStop(lumio, causedByOwnLaunch = true))
        assertFalse(LockRules.lockOnStop(lumioSwitch, causedByOwnLaunch = true))
    }

    @Test
    fun theIdleCountdownRunsTheTimeoutFromTheEntry() {
        assertEquals(30 * minute, LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 0, launchReturnedAt = 0, now = 0))
    }

    @Test
    fun theIdleLockIsDueOnceTheTimeoutPassesWithoutATouch() {
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 0, launchReturnedAt = 0, now = 30 * minute - 1) > 0)
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 0, launchReturnedAt = 0, now = 30 * minute) <= 0)
    }

    @Test
    fun aTouchRestartsTheCountdown() {
        // Touched at twenty minutes: the lock is due at fifty minutes, not at thirty.
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 20 * minute, launchReturnedAt = 0, now = 49 * minute) > 0)
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 20 * minute, launchReturnedAt = 0, now = 50 * minute) <= 0)
    }

    @Test
    fun aTouchBeforeTheEntryDoesNotCount() {
        // Touched on the camera at ten minutes, the hidden area opened at twenty-five: the countdown starts at the entry.
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 25 * minute, lastTouch = 10 * minute, launchReturnedAt = 0, now = 40 * minute) > 0)
    }

    @Test
    fun timeAwayInTheBackgroundCountsTowardTheTimeout() {
        // Nothing touches the app and it is stopped: a stop does not pause the countdown, so the lock is due at thirty minutes.
        assertTrue(LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 0, launchReturnedAt = 0, now = 30 * minute) <= 0)
    }

    @Test
    fun anOwnLaunchPausesTheCountdownForLumio() {
        assertTrue(LockRules.idlePaused(lumio, launchOpen = true))
        assertFalse(LockRules.idlePaused(lumio, launchOpen = false))
    }

    @Test
    fun theReturnOfAnOwnLaunchCountsAsActivity() {
        // The launch returned at forty minutes. At sixty, ten minutes of the thirty are left.
        assertEquals(10 * minute, LockRules.idleRemainingMs(lumio, enteredAt = 0, lastTouch = 0, launchReturnedAt = 40 * minute, now = 60 * minute))
    }

    @Test
    fun theReturnOfAnOwnLaunchDoesNotCountForLensly() {
        // Lensly's countdown has always run from the last touch, so the return of a launch changes nothing for it.
        assertTrue(LockRules.idleRemainingMs(LockPolicy.LENSLY, enteredAt = 0, lastTouch = 0, launchReturnedAt = 40 * minute, now = 60 * minute) <= 0)
    }
}
