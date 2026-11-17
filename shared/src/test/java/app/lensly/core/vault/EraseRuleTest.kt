package app.lensly.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
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
    fun theWarningStartsThreeTriesBeforeTheErase() {
        // With five allowed tries, two wrong tries leave three, and the fifth wrong try erases.
        assertNull(FailureDelay.triesLeftToWarn(state(1, wipeAfter = 5)))
        assertEquals(3, FailureDelay.triesLeftToWarn(state(2, wipeAfter = 5)))
        assertEquals(2, FailureDelay.triesLeftToWarn(state(3, wipeAfter = 5)))
        assertEquals(1, FailureDelay.triesLeftToWarn(state(4, wipeAfter = 5)))
        assertNull(FailureDelay.triesLeftToWarn(state(5, wipeAfter = 5)))
    }

    @Test
    fun aTamperedStateIsNeverErasedAndShowsNoWarning() {
        val tampered = state(FailureStore.TAMPER_FAILURES + 1, wipeAfter = 5)
        assertFalse(FailureDelay.erases(tampered))
        assertNull(FailureDelay.triesLeftToWarn(tampered))
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
    fun theEraseCountIsBoundedToFiveThroughTwenty() {
        val dir = tmp.newFolder("failures")
        val store = FailureStore(dir, keys)
        store.initialize(clock)
        val state = store.load(clock)

        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, null, 4, clock) }
        assertFailsWith<IllegalArgumentException> { store.recordLimits(state, null, 21, clock) }
        assertEquals(5, store.recordLimits(state, null, 5, clock).wipeAfter)
        assertEquals(20, store.recordLimits(store.load(clock), null, 20, clock).wipeAfter)
    }

    @Test
    fun theCapCannotBeSetBelowTenSeconds() {
        assertFailsWith<IllegalArgumentException> { FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = 9_999L) }
        assertEquals(FailureDelay.MIN_CAP_MS, FailureDelay(vaultCreatedWallMs = 0L, configuredCapMs = 10_000L).capMs(Long.MAX_VALUE))
    }
}
