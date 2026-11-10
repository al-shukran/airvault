package app.lensly.core.vault

import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FailureStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val macKey = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "HmacSHA256")

    private fun open(dir: File) = FailureStore(dir, macKey)

    private fun File.primary() = File(this, FailureStore.PRIMARY_FILE_NAME)

    private fun File.backup() = File(this, FailureStore.BACKUP_FILE_NAME)

    @Test
    fun initialStateLoadsFromAFreshInstance() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun failuresAndSuccessesArePersisted() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)

        val afterOne = store.recordFailure(store.load(clock), clock)
        store.recordFailure(afterOne, clock)
        assertEquals(2, open(dir).load(clock).failures)

        store.recordSuccess(open(dir).load(clock))
        assertEquals(0, open(dir).load(clock).failures)
    }

    @Test
    fun recordSuccessWithoutFailuresChangesNothing() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val state = store.load(clock)
        assertEquals(state, store.recordSuccess(state))
    }

    @Test
    fun editedPrimaryCopyLocksForTheFullCap() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        val bytes = dir.primary().readBytes()
        bytes[10] = (bytes[10].toInt() xor 0x01).toByte()
        dir.primary().writeBytes(bytes)

        val delay = FailureDelay(FakeClock.START_WALL)
        val locked = open(dir).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock))
    }

    @Test
    fun missingBackupCopyIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        open(dir).initialize(clock)
        dir.backup().delete()
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackOnlyThePrimaryCopyIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val older = dir.primary().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(older)
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun rollingBackBothCopiesIsDetectedWhileTheProcessIsAlive() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        val olderPrimary = dir.primary().readBytes()
        val olderBackup = dir.backup().readBytes()
        store.recordFailure(store.load(clock), clock)

        dir.primary().writeBytes(olderPrimary)
        dir.backup().writeBytes(olderBackup)
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }

    @Test
    fun wallClockMovedBackwardIsTampering() {
        val dir = File(tmp.root, "vault")
        val clock = FakeClock()
        val store = open(dir)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        clock.wall -= 5
        assertEquals(FailureStore.TAMPER_FAILURES, open(dir).load(clock).failures)
    }
}
