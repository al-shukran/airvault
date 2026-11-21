package app.lensly.core.vault

import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Failure Delay through [Vault.unlock]: a restored older state and a reboot. Each
 * test restarts the vault by forgetting the in-process sequence number and building a new [Vault] over the same files and keys.
 */
class VaultRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private var bootCount: Int? = 7
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = newVault()
    }

    @Test
    fun aRestoredOlderDelayStateErasesTheVaultAtTheNextAttemptEvenWithTheRightPassword() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        val beforeTheGuesses = snapshotFailureFiles()
        assertRejected(attempt(WRONG))
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertRejected(attempt(WRONG))

        restoreFailureFiles(beforeTheGuesses)
        restart()

        // The restored copies name keys that were deleted, so no copy verifies: the count is unknown, and the attempt erases the vault.
        assertEquals(UnlockResult.Erased, attempt(APP))
        assertFalse(layout.root.exists())
    }

    @Test
    fun aRebootWithTheClockMovedForwardDoesNotEndTheWaitEarly() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertRejected(attempt(WRONG))

        // The device reboots: the boot count goes up by one, the monotonic clock restarts, and the wall clock is moved forward
        // an hour.
        bootCount = 8
        clock.elapsed = 2_000L
        clock.wall += FailureDelay.MAX_CAP_MS
        restart()

        // Two seconds into the boot, of the 10 s wait, so the right password is still refused.
        assertRejected(attempt(APP))
        clock.advance(8_000)
        unlocked(attempt(APP)).close()
    }

    @Test
    fun aCorrectUnlockResetsTheCount() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertRejected(attempt(WRONG))
        assertEquals(1, vault.failureCountForTesting())

        // The wait for one failure is 10 s. Past it, the right password opens the vault and clears the count.
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        unlocked(attempt(APP)).close()
        assertEquals(0, vault.failureCountForTesting())
    }

    @Test
    fun aCorrectPasswordIsRefusedWithoutAVerdictWhileTheKeystoreWillNotMakeANewDelayStateKey() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertRejected(attempt(WRONG))
        // Past the 10 s wait, so the next attempt is checked.
        clock.advance(FailureDelay.LEARNING_CAP_MS)

        // From here the Keystore refuses to make the key that a count rotates in. The try cannot be counted, so the password is not
        // checked, and the right password is refused with no verdict. The count stays as it was.
        val refusing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey = throw GeneralSecurityException("no new key")
        }
        FailureStore.forget(layout.root)
        vault = Vault(layout, refusing, clock, KDF, readBootCount = { bootCount })
        assertRejected(attempt(APP))
        assertEquals(1, vault.failureCountForTesting())

        // Once the Keystore makes keys again, the right password opens the vault and clears the count.
        restart()
        unlocked(attempt(APP)).close()
        assertEquals(0, vault.failureCountForTesting())
    }

    @Test
    fun aCheckMadeDuringTheWaitIsRememberedAcrossAReboot() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        // One failure: a 10 s wait.
        assertRejected(attempt(WRONG))

        // Four seconds into the wait, an attempt is checked, and the 4 s served are saved with the state.
        clock.advance(4_000)
        assertRejected(attempt(APP))

        // The device reboots with the wall clock moved forward an hour. This boot is three seconds old, and the 4 s served before
        // the reboot still count: 7 s of the 10 s wait have run, so 3 s are left.
        bootCount = 8
        clock.elapsed = 3_000L
        clock.wall += FailureDelay.MAX_CAP_MS
        restart()
        assertRejected(attempt(APP))
        clock.advance(2_999)
        assertRejected(attempt(APP))
        clock.advance(1)
        unlocked(attempt(APP)).close()
    }

    @Test
    fun aBootCountRaisedWithoutARebootDoesNotEndTheWaitEarly() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertRejected(attempt(WRONG))

        // The attacker writes BOOT_COUNT up by one. The device has not rebooted, so the monotonic clock keeps counting.
        bootCount = 8
        clock.advance(1_000)
        assertRejected(attempt(APP))
        clock.advance(8_999)
        assertRejected(attempt(APP))
        clock.advance(1)
        unlocked(attempt(APP)).close()
    }

    @Test
    fun aLostDelayStateKeyErasesTheVaultAtTheNextAttemptEvenWithTheRightPassword() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        assertRejected(attempt(WRONG))
        clock.advance(FailureDelay.LEARNING_CAP_MS)

        // The Keystore loses every Delay State key, and refuses to make another, so the state can be neither read nor saved.
        keys.deleteFailureMacKeysExcept(null)
        val refusing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey = throw GeneralSecurityException("no key")
        }
        vault = Vault(layout, refusing, clock, KDF, readBootCount = { bootCount })

        // No copy verifies, so the count is unknown, and the attempt erases the vault before the password is checked.
        assertEquals(UnlockResult.Erased, attempt(APP))
        assertFalse(layout.root.exists())
    }

    @Test
    fun aTryWhoseCountCannotBeSavedIsRefusedWithoutAVerdictAcrossARestart() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        // Every write of the Delay State fails, as on a full disk: the vault folder takes no new file, so no temp file
        // can be made. Reads still work. A root user can write to a read-only folder, so the test is skipped there.
        layout.root.setWritable(false)
        try {
            assumeFalse(layout.root.canWrite())

            // The try is counted before its password is checked. With the count unsaved, no verdict is given, so a wrong password
            // is refused, and so is the right one. Nothing is counted, and nothing is erased.
            assertRejected(attempt(WRONG))
            assertRejected(attempt(APP))

            // A new process reads the same copies, and the storage still refuses the count, so no guess gets a verdict.
            restart()
            assertRejected(attempt(APP))
            assertRejected(attempt(WRONG))
            assertEquals(0, vault.failureCountForTesting())
            assertTrue(vault.exists())
        } finally {
            layout.root.setWritable(true)
        }

        // Once the folder takes writes again, the right password opens the vault. The refused tries were neither counted nor checked.
        unlocked(attempt(APP)).close()
    }

    @Test
    fun aTryThatFailsBeforeItsVerdictStillCounts() {
        createVault()
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        // The Keystore fails when the slot key is used. That is after the try is counted and before its password is checked, so the
        // try ends with an error and no verdict.
        val failingSlot = object : KeystoreBackend by keys {
            override fun slotMac(message: ByteArray): ByteArray = throw ProviderException("the slot key is gone")
        }
        vault = Vault(layout, failingSlot, clock, KDF, readBootCount = { bootCount })
        assertFailsWith<VaultException> { attempt(WRONG) }

        // A new process reads the stored count, which includes the try that ended without a verdict.
        restart()
        assertEquals(1, vault.failureCountForTesting())
    }

    private fun createVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun newVault() = Vault(layout, keys, clock, KDF, readBootCount = { bootCount })

    /** Forgets the in-process sequence number, then builds a new vault over the same folder and keys. */
    private fun restart() {
        FailureStore.forget(layout.root)
        vault = newVault()
    }

    private fun attempt(password: String): UnlockResult = runBlocking { vault.unlock(password.toCharArray()) }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertEquals(UnlockResult.Rejected, result)
    }

    private fun snapshotFailureFiles(): Map<String, ByteArray> =
        listOf(FailureStore.PRIMARY_FILE_NAME, FailureStore.BACKUP_FILE_NAME)
            .associateWith { File(layout.root, it).readBytes() }

    private fun restoreFailureFiles(snapshot: Map<String, ByteArray>) {
        snapshot.forEach { (name, bytes) -> File(layout.root, name).writeBytes(bytes) }
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
