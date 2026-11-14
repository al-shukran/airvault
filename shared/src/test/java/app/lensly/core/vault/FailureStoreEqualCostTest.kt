package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A passing check must write the failure state as many times as a failed check. Otherwise the storage work alone tells
 * an observer whether an unlock succeeded.
 */
class FailureStoreEqualCostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val failureKeys = MemoryKeystore()
    private val successKeys = MemoryKeystore()

    private class Counter {
        var writes = 0

        fun write(target: File, bytes: ByteArray) {
            writes++
            writeAtomically(target, bytes)
        }
    }

    @Test
    fun successWithoutFailuresWritesAsMuchAsAFailure() {
        val clock = FakeClock()
        val failure = Counter()
        val success = Counter()
        val failureStore = FailureStore(File(tmp.root, "failure"), failureKeys, write = failure::write)
        val successStore = FailureStore(File(tmp.root, "success"), successKeys, write = success::write)
        failureStore.initialize(clock)
        successStore.initialize(clock)
        failure.writes = 0
        success.writes = 0

        failureStore.recordFailure(failureStore.load(clock), clock)
        successStore.recordSuccess(successStore.load(clock), clock)

        assertEquals(2, failure.writes, "one failure writes the primary and the backup copy")
        assertEquals(failure.writes, success.writes, "a passing check must cost the same storage work as a failure")
    }

    @Test
    fun aBlockedAttemptWritesTheSameWhetherOrNotBootCountIsReadable() {
        val clock = FakeClock()
        val readable = Counter()
        val unreadable = Counter()
        val withCount = FailureStore(File(tmp.root, "readable"), failureKeys, { 7 }, write = readable::write)
        val withoutCount = FailureStore(File(tmp.root, "unreadable"), successKeys, { null }, write = unreadable::write)
        withCount.initialize(clock)
        withoutCount.initialize(clock)
        withCount.recordFailure(withCount.load(clock), clock)
        withoutCount.recordFailure(withoutCount.load(clock), clock)
        clock.advance(4_000)
        readable.writes = 0
        unreadable.writes = 0

        // Both attempts are inside the 10 s wait, so both are blocked and both save the time served.
        assertTrue(withCount.checkDelay(clock, FakeClock.START_WALL).blocked)
        assertTrue(withoutCount.checkDelay(clock, FakeClock.START_WALL).blocked)
        assertEquals(2, readable.writes, "a blocked attempt saves once: the primary and the backup copy")
        assertEquals(readable.writes, unreadable.writes, "an unreadable BOOT_COUNT must not change the storage work")
    }
}
