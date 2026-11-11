package app.lensly.core.vault

import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Failure Delay through [Vault.unlock]: a restored older state, a reboot, and an upgrade from the single-key layout. Each
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
    fun aRestoredOlderDelayStateIsTamperedAfterARestartAndWaitsTheFullCap() {
        createVault()
        // Past the learning period, so the owner's default cap applies and the tamper cap is the one-hour cap.
        clock.advance(FailureDelay.LEARNING_PERIOD_MS)
        val beforeTheGuesses = snapshotFailureFiles()
        assertRejected(attempt(WRONG))
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertRejected(attempt(WRONG))

        restoreFailureFiles(beforeTheGuesses)
        restart()

        // The restored copies name keys that were deleted, so they are tampered: the right password waits the one-hour cap.
        assertRejected(attempt(APP))
        clock.advance(FailureDelay.MAX_CAP_MS - 1)
        assertRejected(attempt(APP))
        clock.advance(1)
        unlocked(attempt(APP)).close()
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
    fun aPendingWaitFromTheSingleKeyLayoutSurvivesTheMigration() {
        createVault()
        // The state the app wrote before per-save keys: two failures, with one legacy key that is the only one left.
        val legacyState = FailureState(
            seq = 5,
            failures = 2,
            lastFailureWallMs = clock.wall,
            lastFailureElapsedMs = clock.elapsed,
        )
        keys.deleteFailureMacKeysExcept(null)
        val legacyKey = keys.createLegacyFailureMacKey()
        val legacy = encodeLegacyV2(legacyState, legacyKey)
        File(layout.root, FailureStore.PRIMARY_FILE_NAME).writeBytes(legacy)
        File(layout.root, FailureStore.BACKUP_FILE_NAME).writeBytes(legacy)
        restart()

        // Inside the 10 s wait for two failures, so even the right password is refused. The migration happens on this attempt.
        assertRejected(attempt(APP))
        assertNull(keys.legacyFailureMacKey())
        clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
        assertRejected(attempt(APP))
        clock.advance(1)
        unlocked(attempt(APP)).close()
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

    /** A version 2 record under [key]: the layout the app wrote before per-save keys. */
    private fun encodeLegacyV2(state: FailureState, key: SecretKey): ByteArray {
        val payload = ByteBuffer.allocate(41)
            .put("LNFS".toByteArray(Charsets.US_ASCII))
            .put(2)
            .putLong(state.seq)
            .putInt(state.failures)
            .putLong(state.lastFailureWallMs)
            .putLong(state.lastFailureElapsedMs)
            .putLong(state.capMs ?: 0L)
            .array()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(payload)
        }
        return payload + mac
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
