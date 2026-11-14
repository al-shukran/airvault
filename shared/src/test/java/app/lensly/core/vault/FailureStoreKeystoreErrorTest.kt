package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Keystore errors on the Failure Delay state. On the load path, an error makes the copy unverified, so the state locks at the
 * strictest cap and no exception escapes. On the success save, a correct password still unlocks.
 */
class FailureStoreKeystoreErrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private val flaky = FlakyKeystore(keys)

    private fun File.primary() = File(this, FailureStore.PRIMARY_FILE_NAME)

    private fun File.backup() = File(this, FailureStore.BACKUP_FILE_NAME)

    /**
     * A keystore whose Delay State key calls fail when a test asks, the way a Keystore error does. A refused key creation also
     * makes the reads fail from then on, which is the case where the Keystore stops working during a save.
     */
    private class FlakyKeystore(private val inner: KeystoreBackend) : KeystoreBackend by inner {
        var failReads = false
        var refuseNewKeys = false

        override fun createFailureMacKey(seq: Long): SecretKey {
            if (refuseNewKeys) {
                failReads = true
                throw GeneralSecurityException("the Keystore refused a new key")
            }
            return inner.createFailureMacKey(seq)
        }

        override fun failureMacKey(seq: Long): SecretKey? {
            if (failReads) throw ProviderException("the key could not be read")
            return inner.failureMacKey(seq)
        }

        override fun legacyFailureMacKey(): SecretKey? {
            if (failReads) throw ProviderException("the key could not be read")
            return inner.legacyFailureMacKey()
        }
    }

    @Test
    fun aKeyReadFailureOnLoadLocksAtTheStrictestCapWithoutAnException() {
        val dir = File(tmp.root, "vault")
        val store = FailureStore(dir, flaky)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)
        // Past the learning period, so the one-hour cap is the strictest one in force.
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)

        flaky.failReads = true
        val locked = FailureStore(dir, flaky).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
        val delay = FailureDelay(FakeClock.START_WALL, FailureDelay.configuredCapFor(locked))
        clock.advance(FailureDelay.MAX_CAP_MS - 1)
        assertTrue(delay.isBlocked(locked, clock))
        clock.advance(1)
        assertFalse(delay.isBlocked(locked, clock))
    }

    @Test
    fun aLoadThatCannotReadOrMakeAKeyStillLocksAtTheStrictestCap() {
        val dir = File(tmp.root, "vault")
        FailureStore(dir, flaky).initialize(clock)
        flaky.failReads = true
        flaky.refuseNewKeys = true

        // Nothing can be verified or saved, so the lockout applies to this load at the strictest cap.
        val locked = FailureStore(dir, flaky).load(clock)
        assertEquals(FailureStore.TAMPER_FAILURES, locked.failures)
        assertEquals(FailureDelay.MAX_CAP_MS, locked.capMs)
    }

    @Test
    fun aResetThatCannotRotateTheKeyIsNeverWrittenUnderTheOldOne() {
        val dir = File(tmp.root, "vault")
        val store = FailureStore(dir, flaky)
        store.initialize(clock)
        val failed = store.recordFailure(store.load(clock), clock)
        val primaryBefore = dir.primary().readBytes()
        val backupBefore = dir.backup().readBytes()

        flaky.refuseNewKeys = true
        flaky.failReads = true
        // The reset cannot make its key, so it is kept in memory for this process, and nothing is written under the key on disk.
        assertEquals(0, store.recordSuccess(failed, clock).failures)
        assertTrue(primaryBefore.contentEquals(dir.primary().readBytes()))
        assertTrue(backupBefore.contentEquals(dir.backup().readBytes()))

        // A new process sees the stored count, the stricter one, once the Keystore works again.
        flaky.refuseNewKeys = false
        flaky.failReads = false
        FailureStore.forget(dir)
        assertEquals(1, FailureStore(dir, flaky).load(clock).failures)
    }

    @Test
    fun aPersistentKeyCreationFailureDoesNotExtendTheLockFromEachAttempt() {
        val dir = File(tmp.root, "vault")
        FailureStore(dir, flaky).initialize(clock)
        // Past the learning period, so the strictest cap is the one that applies.
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        // The copies are lost, and the Keystore refuses every new key, so the lockout cannot be saved.
        dir.primary().delete()
        dir.backup().delete()
        flaky.refuseNewKeys = true

        // The first attempt locks for one hour, from now.
        assertTrue(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
        // Attempts inside that hour do not start another one, so the end of the wait does not move.
        clock.advance(FailureDelay.MAX_CAP_MS - 1)
        assertTrue(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        // The hour has run out, so the owner is no longer held.
        assertFalse(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun aKeyThatCannotBeReadAfterItsLockIsSavedDoesNotLockForever() {
        val dir = File(tmp.root, "vault")
        FailureStore(dir, flaky).initialize(clock)
        // Past the learning period, so the strictest cap is the one that applies.
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        // The Keystore can make keys but cannot read them. The copies cannot be verified, so the lock is saved, and it stays saved.
        flaky.failReads = true
        assertTrue(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
        // Checks inside the hour are still blocked, and none of them starts another hour.
        clock.advance(FailureDelay.MAX_CAP_MS - 1)
        assertTrue(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
        clock.advance(1)
        // The hour has run out. The owner is not held for good, even though the Keystore still cannot read the key.
        assertFalse(FailureStore(dir, flaky).checkDelay(clock, FakeClock.START_WALL).blocked)
    }

    @Test
    fun anIOExceptionFromTheKeystoreNeverEscapesTheDelayCheck() {
        val dir = File(tmp.root, "vault")
        val io = IoFailingKeystore(keys)
        val store = FailureStore(dir, io)
        store.initialize(clock)
        store.recordFailure(store.load(clock), clock)

        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        io.failing = true
        // Every call returns: the check blocks the attempt, a failure that cannot be saved locks at the strictest cap, and a reset that
        // cannot be saved is kept in memory. Nothing is thrown.
        val check = FailureStore(dir, io).checkDelay(clock, FakeClock.START_WALL)
        assertTrue(check.blocked)
        assertEquals(FailureStore.TAMPER_FAILURES, FailureStore(dir, io).recordFailureOrLock(check.state, clock).failures)
        assertTrue(FailureStore(dir, io).checkDelay(clock, FakeClock.START_WALL).blocked)

        clock.advance(FailureDelay.MAX_CAP_MS)
        val passed = FailureStore(dir, io).checkDelay(clock, FakeClock.START_WALL)
        assertFalse(passed.blocked)
        assertEquals(0, FailureStore(dir, io).recordSuccess(passed.state, clock).failures)
    }

    @Test
    fun aCorrectPasswordUnlocksWhenTheKeystoreFailsDuringTheSuccessSave() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val vault = Vault(layout, flaky, clock, KDF, readBootCount = { 7 })
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertEquals(UnlockResult.Rejected, runBlocking { vault.unlock(WRONG.toCharArray()) })

        // Past the 10 s wait for one failure. The Keystore refuses the key for the reset, and then cannot read the key it has.
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        flaky.refuseNewKeys = true
        val result = runBlocking { vault.unlock(APP.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        (result as UnlockResult.Unlocked).session.close()
    }

    @Test
    fun aKeyReadFailureOnTheLoadPathIsADelayNotAnError() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val vault = Vault(layout, flaky, clock, KDF, readBootCount = { 7 })
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)

        // The state cannot be verified, so it locks at the one-hour cap. The right password waits, and gets no error.
        flaky.failReads = true
        assertEquals(UnlockResult.Rejected, runBlocking { vault.unlock(APP.toCharArray()) })
        flaky.failReads = false
        assertEquals(FailureStore.TAMPER_FAILURES, vault.failureCountForTesting())
    }

    /** A keystore whose Delay State key calls throw an IOException, as a Keystore service that has died can. */
    private class IoFailingKeystore(private val inner: KeystoreBackend) : KeystoreBackend by inner {
        var failing = false

        private fun fail(): Nothing = throw IOException("the Keystore service is gone")

        override fun createFailureMacKey(seq: Long): SecretKey = if (failing) fail() else inner.createFailureMacKey(seq)

        override fun failureMacKey(seq: Long): SecretKey? = if (failing) fail() else inner.failureMacKey(seq)

        override fun legacyFailureMacKey(): SecretKey? = if (failing) fail() else inner.legacyFailureMacKey()

        override fun failureMacKeySeqs(): List<Long> = if (failing) fail() else inner.failureMacKeySeqs()

        override fun deleteFailureMacKeysExcept(keepSeq: Long?) = if (failing) fail() else inner.deleteFailureMacKeysExcept(keepSeq)

        override fun deleteFailureMacKey(seq: Long) = if (failing) fail() else inner.deleteFailureMacKey(seq)
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
