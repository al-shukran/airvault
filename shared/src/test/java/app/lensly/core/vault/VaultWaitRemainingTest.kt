package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Vault.waitRemainingMs], the read-only countdown of the Failure Delay. It reports the wait the next try would face, and nothing it does
 * changes the stored state: the count, the wait and the saved files stay as they were, however often it is asked.
 */
class VaultWaitRemainingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    @Test
    fun noWaitRunsBeforeAWrongTry() {
        assertEquals(0L, remaining())
    }

    @Test
    fun askingAgainChangesNothing() {
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, wipeAfter = 3) })
        assertEquals(UnlockResult.Rejected, attemptAfterWait(WRONG))
        assertEquals(UnlockResult.Rejected, attemptAfterWait(WRONG))
        val filesBefore = failureFiles()
        val first = remaining()
        assertTrue(first > 0L)

        repeat(N_READS) { assertEquals(first, remaining()) }

        // The reads counted no try: with an erase count of 3, the second wrong try leaves one try, and the vault still stands.
        assertEquals(filesBefore, failureFiles())
        assertEquals(1, runBlocking { vault.triesBeforeErase() })
    }

    @Test
    fun theWaitCountsDownWithTheClockAndEndsAtZero() {
        assertEquals(UnlockResult.Rejected, attemptAfterWait(WRONG))
        assertEquals(FIRST_WAIT_MS, remaining())

        clock.advance(4_000L)
        assertEquals(FIRST_WAIT_MS - 4_000L, remaining())

        clock.advance(FIRST_WAIT_MS)
        assertEquals(0L, remaining())
    }

    private fun remaining(): Long = runBlocking { vault.waitRemainingMs() }

    /** Moves the clock past any wait, then tries [password]. */
    private fun attemptAfterWait(password: String): UnlockResult {
        clock.advance(MAX_WAIT)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    /** The bytes of both copies of the failure state, as lists so that equal contents compare equal. */
    private fun failureFiles(): Map<String, List<Byte>> =
        listOf(FailureStore.PRIMARY_FILE_NAME, FailureStore.BACKUP_FILE_NAME)
            .associateWith { File(layout.root, it).readBytes().toList() }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val N_READS = 5
        /** The wait after the first wrong try: the Failure Delay's base delay. */
        const val FIRST_WAIT_MS = FailureDelay.BASE_DELAY_MS
        const val MAX_WAIT = FailureDelay.MAX_CAP_MS + 1
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
