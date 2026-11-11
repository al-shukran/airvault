package app.lensly.core.vault

import java.io.File
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
    fun aKeyReadFailureOnTheSuccessSaveSkipsTheResetAndDoesNotThrow() {
        val dir = File(tmp.root, "vault")
        val store = FailureStore(dir, flaky)
        store.initialize(clock)
        val failed = store.recordFailure(store.load(clock), clock)

        flaky.refuseNewKeys = true
        flaky.failReads = true
        val after = store.recordSuccess(failed, clock)
        assertEquals(failed.failures, after.failures)

        // Nothing was written, so the copies still hold the one failure and verify once the Keystore works again.
        flaky.refuseNewKeys = false
        flaky.failReads = false
        assertEquals(1, FailureStore(dir, flaky).load(clock).failures)
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

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
