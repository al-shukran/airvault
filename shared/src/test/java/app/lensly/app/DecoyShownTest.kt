package app.lensly.app

import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultRecordNames
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What [DecoyShown] reads back from the decoy's own record: a good record, and every record it must not trust. */
class DecoyShownTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: VaultSession

    @Before
    fun setUp() {
        session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 7).toByte() }, isDecoy = true)
    }

    @Test
    fun aKeptRecordReadsBackInTheNextSession() {
        val shown = DecoyShown()
        shown.open(session)
        assertTrue(shown.showFailureLimits(10_000L, 5))

        val next = DecoyShown()
        next.open(session)

        assertEquals(10_000L, next.failureCapMs)
        assertEquals(5, next.failureEraseAfter)
    }

    @Test
    fun aRecordOfAnotherVersionReadsAsNoChange() {
        keep(record(version = 1, capMs = 10_000L, eraseAfter = 5))

        assertDefaults()
    }

    @Test
    fun aTruncatedRecordReadsAsNoChange() {
        keep(record(version = 2, capMs = 10_000L, eraseAfter = 5).copyOf(5))

        assertDefaults()
    }

    @Test
    fun anEraseCountOutsideTheAllowedRangeReadsAsNoChange() {
        for (eraseAfter in listOf(FailureDelay.MIN_WIPE_AFTER - 1, FailureDelay.MAX_WIPE_AFTER + 1, 0, -1, Int.MAX_VALUE)) {
            keep(record(version = 2, capMs = 10_000L, eraseAfter = eraseAfter))

            assertDefaults()
        }
    }

    private fun keep(bytes: ByteArray) = session.putVaultRecord(VaultRecordNames.DECOY_SHOWN, bytes)

    private fun assertDefaults() {
        val shown = DecoyShown()
        shown.open(session)

        assertNull(shown.failureCapMs)
        assertEquals(FailureDelay.DEFAULT_WIPE_AFTER, shown.failureEraseAfter)
    }

    private fun record(version: Int, capMs: Long, eraseAfter: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(version)
            out.writeLong(capMs)
            out.writeInt(eraseAfter)
        }
        return buffer.toByteArray()
    }
}
