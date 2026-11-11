package app.lensly.core.vault

import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
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

    private val macKey = SecretKeySpec(ByteArray(32) { (it * 11 + 3).toByte() }, "HmacSHA256")

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
        val failureStore = FailureStore(File(tmp.root, "failure"), macKey, failure::write)
        val successStore = FailureStore(File(tmp.root, "success"), macKey, success::write)
        failureStore.initialize(clock)
        successStore.initialize(clock)
        failure.writes = 0
        success.writes = 0

        failureStore.recordFailure(failureStore.load(clock), clock)
        successStore.recordSuccess(successStore.load(clock))

        assertEquals(2, failure.writes, "one failure writes the primary and the backup copy")
        assertEquals(failure.writes, success.writes, "a passing check must cost the same storage work as a failure")
    }
}
