package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The transaction settings: their defaults, what the vault keeps of them, and what it reads back when a value does not fit. */
class TransactionSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    // A session zeroes the key it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 5 + 11).toByte() })

    @Test
    fun theDefaultsAreWhatTheOwnerSeesBeforeAnyChange() {
        val session = open()

        assertEquals(TransactionSettings(hideSpam = true, lastChanceSeconds = 3), TransactionSettingsStore.read(session))
        session.close()
    }

    @Test
    fun aSavedChangeIsReadBackAfterTheVaultReopens() {
        val session = open()
        TransactionSettingsStore.write(session, TransactionSettings(hideSpam = false, lastChanceSeconds = 10))
        session.close()

        val reopened = open()
        assertEquals(TransactionSettings(hideSpam = false, lastChanceSeconds = 10), TransactionSettingsStore.read(reopened))
        reopened.close()
    }

    @Test
    fun aPauseOutsideTheChoicesReadsAsTheDefaultPause() {
        val session = open()
        session.putVaultRecord(TransactionSettingsStore.RECORD, """{"hideSpam":false,"lastChanceSeconds":99}""".encodeToByteArray())

        assertEquals(TransactionSettings(hideSpam = false, lastChanceSeconds = 3), TransactionSettingsStore.read(session))
        session.close()
    }
}
