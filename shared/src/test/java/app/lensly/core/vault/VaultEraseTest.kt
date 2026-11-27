package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The erase after the owner's wrong tries in a row, through the vault: the wipe on the last allowed try, the warning, the reset on a
 * right password, and the same outcome whether or not a Decoy Vault exists.
 */
class VaultEraseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun theLastAllowedWrongTryErasesTheVaultAndNothingOpensIt() {
        createVault()
        setEraseAfter(5)

        repeat(4) { assertRejected(attempt(WRONG)) }
        assertEquals(UnlockResult.Erased, attempt(WRONG))

        assertFalse(layout.root.exists())
        assertFalse(vault.exists())
        assertNull(runBlocking { vault.triesBeforeErase() })
        assertRejected(attempt(APP))
    }

    @Test
    fun theLastAllowedWrongTryShredsAPlainVideoThatAnInterruptedRecordingLeft() {
        val recording = File(tmp.root, "cache/recording.mp4").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(64) { 7 })
        }
        layout = VaultLayout(File(tmp.root, "vault"), recording = recording)
        vault = Vault(layout, keys, clock, KDF)
        createVault()
        setEraseAfter(3)

        repeat(2) { assertRejected(attempt(WRONG)) }
        assertTrue(recording.exists())
        assertEquals(UnlockResult.Erased, attempt(WRONG))

        assertFalse(recording.exists())
    }

    @Test
    fun aKeyDeleteFailureStillErasesTheFilesOnTheLastWrongTry() {
        createVault()
        setEraseAfter(3)
        keys.failDeleteAll = true

        repeat(2) { assertRejected(attempt(WRONG)) }
        assertEquals(UnlockResult.Erased, attempt(WRONG))

        assertFalse(layout.root.exists())
        assertFalse(vault.exists())
    }

    @Test
    fun theLowestEraseCountErasesOnTheThirdWrongTry() {
        createVault()
        setEraseAfter(3)

        assertNull(runBlocking { vault.triesBeforeErase() })
        assertRejected(attempt(WRONG))
        assertEquals(2, runBlocking { vault.triesBeforeErase() })
        assertRejected(attempt(WRONG))
        assertEquals(1, runBlocking { vault.triesBeforeErase() })
        assertEquals(UnlockResult.Erased, attempt(WRONG))

        assertFalse(vault.exists())
    }

    @Test
    fun theWarningShowsFromThreeTriesBeforeTheErase() {
        createVault()
        setEraseAfter(5)

        assertNull(runBlocking { vault.triesBeforeErase() })
        attempt(WRONG)
        assertNull(runBlocking { vault.triesBeforeErase() })
        attempt(WRONG)
        assertEquals(3, runBlocking { vault.triesBeforeErase() })
        attempt(WRONG)
        assertEquals(2, runBlocking { vault.triesBeforeErase() })
        attempt(WRONG)
        assertEquals(1, runBlocking { vault.triesBeforeErase() })
    }

    @Test
    fun aRightPasswordResetsTheCount() {
        createVault()
        setEraseAfter(5)
        repeat(4) { attempt(WRONG) }

        unlocked(attempt(APP)).close()
        repeat(4) { assertRejected(attempt(WRONG)) }

        assertTrue(vault.exists())
        assertEquals(1, runBlocking { vault.triesBeforeErase() })
    }

    @Test
    fun theCountSurvivesARestartOfTheVault() {
        createVault()
        setEraseAfter(5)
        repeat(3) { attempt(WRONG) }

        // A new process: the in-memory state is gone, and the count is read from the stored copies.
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)

        assertEquals(2, runBlocking { vault.triesBeforeErase() })
        assertRejected(attempt(WRONG))
        assertEquals(UnlockResult.Erased, attempt(WRONG))
    }

    @Test
    fun aWrongTryOnTheLockoutScreenCountsAndErasesToo() {
        createVault()
        setEraseAfter(5)
        repeat(4) { attempt(WRONG) }

        clock.advance(MAX_WAIT)
        assertEquals(FailureCapResult.Erased, runBlocking { vault.setFailureLimits(WRONG.toCharArray(), 10_000L, 5) })
        assertFalse(vault.exists())
    }

    @Test
    fun theEraseRemovesTheDecoyTooAndLooksTheSameAsNoDecoy() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
        setEraseAfter(5)

        repeat(4) { assertRejected(attempt(WRONG)) }
        assertEquals(UnlockResult.Erased, attempt(WRONG))

        assertFalse(layout.root.exists())
        assertRejected(attempt(DURESS))
        assertRejected(attempt(APP))
    }

    @Test
    fun theEraseCountIsSetInTheSettingsAndBounded() {
        createVault()
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, 20) })
        assertEquals(20, runBlocking { vault.failureEraseAfter() })

        assertFailsWith<IllegalArgumentException> { runBlocking { vault.setFailureLimits(APP.toCharArray(), null, 2) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { vault.setFailureLimits(APP.toCharArray(), null, 21) } }
        assertEquals(20, runBlocking { vault.failureEraseAfter() })
    }

    @Test
    fun aRestoredOlderPairErasesTheVaultAtTheNextAttemptEvenWithTheRightPassword() {
        createVault()
        setEraseAfter(5)
        val primary = File(layout.root, FailureStore.PRIMARY_FILE_NAME)
        val backup = File(layout.root, FailureStore.BACKUP_FILE_NAME)
        val olderPrimary = primary.readBytes()
        val olderBackup = backup.readBytes()
        assertRejected(attempt(WRONG))

        // Restoring both older copies leaves no copy that verifies. The count is unknown, so the threshold counts as reached.
        primary.writeBytes(olderPrimary)
        backup.writeBytes(olderBackup)
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)

        assertEquals(UnlockResult.Erased, attempt(APP))
        assertFalse(layout.root.exists())
        assertRejected(attempt(APP))
    }

    @Test
    fun aBlockedTryDoesNotAddAndAFullDiskAtTheEraseThresholdErasesNothing() {
        createVault()
        setEraseAfter(3)
        assertRejected(attempt(WRONG))
        assertRejected(attempt(WRONG))

        // A try during the wait is refused and not counted, so the count stays at two.
        assertRejected(runBlocking { vault.unlock(APP.toCharArray()) })
        assertEquals(2, vault.failureCountForTesting())

        // The next try is the owner's last allowed one, and the disk is full, so it cannot be counted. It gets no verdict, and nothing
        // is erased.
        layout.root.setWritable(false)
        try {
            assumeFalse(layout.root.canWrite())
            assertRejected(attempt(WRONG))
            assertRejected(attempt(APP))
            assertTrue(vault.exists())
        } finally {
            layout.root.setWritable(true)
        }

        // With the disk writable again, the right password opens the vault.
        unlocked(attempt(APP)).close()
    }

    @Test
    fun anAttemptWithNoVaultRefusesAndCreatesNothing() {
        assertRejected(attempt(APP))
        assertRejected(attempt(WRONG))
        assertFalse(layout.root.exists())
    }

    private fun createVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    /** Saves [eraseAfter] with the App Password, keeping the default cap. */
    private fun setEraseAfter(eraseAfter: Int) {
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, eraseAfter) })
    }

    /** Moves the clock past any failure delay, then tries [password]. */
    private fun attempt(password: String): UnlockResult {
        clock.advance(MAX_WAIT)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertTrue(result is UnlockResult.Rejected, "Expected Rejected but was $result")
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val MAX_WAIT = FailureDelay.MAX_CAP_MS + 1
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
