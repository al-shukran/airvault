package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The erase count: when it erases, when it warns, what it accepts, and that the count and the count's limit survive a restart. */
class EraseRuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()

    private fun state(failures: Int, wipeAfter: Int = FailureDelay.DEFAULT_WIPE_AFTER) =
        FailureState(seq = 1, failures = failures, lastFailureWallMs = 0L, lastFailureElapsedMs = 0L, wipeAfter = wipeAfter)

    @Test
    fun theDefaultEraseCountIsTen() {
        assertEquals(10, FailureDelay.DEFAULT_WIPE_AFTER)
        assertEquals(10, FailureState(seq = 1, failures = 0, lastFailureWallMs = 0L, lastFailureElapsedMs = 0L).wipeAfter)
    }

    @Test
    fun theVaultIsErasedOnTheLastAllowedWrongTry() {
        assertFalse(FailureDelay.erases(state(0)))
        assertFalse(FailureDelay.erases(state(4, wipeAfter = 5)))
        assertTrue(FailureDelay.erases(state(5, wipeAfter = 5)))
        assertFalse(FailureDelay.erases(state(9)))
        assertTrue(FailureDelay.erases(state(10)))
    }

    @Test
    fun theLowestEraseCountErasesOnTheThirdWrongTryAndWarnsOnlyAfterAWrongTry() {
        assertEquals(3, FailureDelay.MIN_WIPE_AFTER)
        assertFalse(FailureDelay.erases(state(2, wipeAfter = 3)))
        assertTrue(FailureDelay.erases(state(3, wipeAfter = 3)))
        assertNull(FailureDelay.triesLeftToWarn(state(0, wipeAfter = 3)))
        assertEquals(2, FailureDelay.triesLeftToWarn(state(1, wipeAfter = 3)))
        assertEquals(1, FailureDelay.triesLeftToWarn(state(2, wipeAfter = 3)))
    }

    @Test
    fun theWarningStartsThreeTriesBeforeTheErase() {
        // With five allowed tries, two wrong tries leave three, and the fifth wrong try erases.
        assertNull(FailureDelay.triesLeftToWarn(state(1, wipeAfter = 5)))
        assertEquals(3, FailureDelay.triesLeftToWarn(state(2, wipeAfter = 5)))
        assertEquals(2, FailureDelay.triesLeftToWarn(state(3, wipeAfter = 5)))
        assertEquals(1, FailureDelay.triesLeftToWarn(state(4, wipeAfter = 5)))
        assertNull(FailureDelay.triesLeftToWarn(state(5, wipeAfter = 5)))
    }

    @Test
    fun aStateWithNoVerifiedCopyIsAtTheEraseThresholdAndShowsNoWarning() {
        // The count of a state no copy verifies is the tamper count, which is above every erase count (3 to 20).
        val unverified = state(FailureStore.TAMPER_FAILURES, wipeAfter = 3)
        assertTrue(FailureDelay.unverified(unverified))
        assertTrue(FailureDelay.erases(unverified))
        assertNull(FailureDelay.triesLeftToWarn(unverified))
        assertFalse(FailureDelay.unverified(state(FailureDelay.MAX_WIPE_AFTER - 1)))
    }

    @Test
    fun aFreshVaultIsNeitherUnverifiedNorErased() {
        val store = FailureStore(tmp.newFolder("failures"), keys)
        store.initialize(clock)
        val fresh = store.load(clock)
        assertFalse(FailureDelay.unverified(fresh))
        assertFalse(FailureDelay.erases(fresh))
        assertFalse(wrongTry(store).erased)
    }

    @Test
    fun theEraseCountAndTheCapAreSavedAndSurviveARestart() {
        val dir = tmp.newFolder("failures")
        val store = FailureStore(dir, keys)
        store.initialize(clock)
        store.recordLimits(store.load(clock), FailureDelay.MIN_CAP_MS, 15, clock)

        // A new instance reads the copies from disk, as after a restart.
        FailureStore.forget(dir)
        val reopened = FailureStore(dir, keys).load(clock)
        assertEquals(15, reopened.wipeAfter)
        assertEquals(FailureDelay.MIN_CAP_MS, reopened.capMs)
    }

    @Test
    fun theFailureCountSurvivesARestartAndASuccessClearsIt() {
        val dir = tmp.newFolder("failures")
        val store = FailureStore(dir, keys)
        store.initialize(clock)
        var state = store.load(clock)
        repeat(3) { state = store.recordFailure(state, clock) }

        FailureStore.forget(dir)
        val reopened = FailureStore(dir, keys).load(clock)
        assertEquals(3, reopened.failures)

        FailureStore(dir, keys).recordSuccess(reopened, clock)
        FailureStore.forget(dir)
        assertEquals(0, FailureStore(dir, keys).load(clock).failures)
    }

    @Test
    fun theEraseCountIsBoundedToThreeThroughTwenty() {
        val dir = tmp.newFolder("failures")
        val store = FailureStore(dir, keys)
        store.initialize(clock)
        val state = store.load(clock)

        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, null, 2, clock) }
        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, null, 21, clock) }
        assertEquals(3, store.recordLimits(state, null, 3, clock).wipeAfter)
        assertEquals(20, store.recordLimits(store.load(clock), null, 20, clock).wipeAfter)
    }

    /** One attempt as the unlock makes it: refused while a wait runs, otherwise a wrong try. */
    private data class Attempt(val state: FailureState, val erased: Boolean)

    private fun wrongTry(store: FailureStore): Attempt {
        val check = store.checkDelay(clock, FakeClock.START_WALL)
        assertFalse(check.blocked)
        val failed = store.recordFailure(check.state, clock)
        return Attempt(failed, FailureDelay.erases(failed))
    }

    @Test
    fun theNthWrongTryErasesAfterABackwardClockStep() {
        val store = FailureStore(tmp.newFolder("failures"), keys)
        store.initialize(clock)
        store.recordLimits(store.load(clock), null, FailureDelay.MIN_WIPE_AFTER, clock)

        clock.advance(FailureDelay.MAX_CAP_MS)
        assertFalse(wrongTry(store).erased)
        clock.advance(FailureDelay.MAX_CAP_MS)
        assertFalse(wrongTry(store).erased)

        // The clock is set back an hour. The attempt that sees it is refused, and the two wrong tries still count.
        clock.wall -= FailureDelay.MAX_CAP_MS
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(FailureDelay.MAX_CAP_MS)

        // The third wrong try is the owner's last allowed one, so it erases.
        assertTrue(wrongTry(store).erased)
    }

    @Test
    fun theNthWrongTryErasesAfterTheBootCountGoesBack() {
        var bootCount = 8
        val store = FailureStore(tmp.newFolder("failures"), keys, { bootCount })
        store.initialize(clock)
        store.recordLimits(store.load(clock), null, FailureDelay.MIN_WIPE_AFTER, clock)

        clock.advance(FailureDelay.MAX_CAP_MS)
        assertFalse(wrongTry(store).erased)
        clock.advance(FailureDelay.MAX_CAP_MS)
        assertFalse(wrongTry(store).erased)

        // Writing BOOT_COUNT back down is tampering. The attempt that sees it is refused, and the two wrong tries still count.
        bootCount = 7
        assertTrue(store.checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(FailureDelay.MAX_CAP_MS)
        assertTrue(wrongTry(store).erased)
    }

    @Test
    fun theCapCannotBeSetBelowTenSeconds() {
        assertFailsWith<IllegalArgumentException> { FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = 9_999L) }
        assertEquals(FailureDelay.MIN_CAP_MS, FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = 10_000L).capMs(learningWeek = false))
    }
}
