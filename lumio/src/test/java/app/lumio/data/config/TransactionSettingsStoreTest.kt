package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        assertEquals(TransactionSettings(hideSpam = true, lastChanceSeconds = 60), TransactionSettingsStore.read(session))
        session.close()
    }

    @Test
    fun aSavedChangeIsReadBackAfterTheVaultReopens() {
        val session = open()
        TransactionSettingsStore.write(session, TransactionSettings(hideSpam = false, lastChanceSeconds = 120))
        session.close()

        val reopened = open()
        assertEquals(TransactionSettings(hideSpam = false, lastChanceSeconds = 120), TransactionSettingsStore.read(reopened))
        reopened.close()
    }

    @Test
    fun aPauseOutsideTheChoicesReadsAsTheDefaultPause() {
        val session = open()
        session.putVaultRecord(TransactionSettingsStore.RECORD, """{"hideSpam":false,"lastChanceSeconds":99}""".encodeToByteArray())

        assertEquals(TransactionSettings(hideSpam = false, lastChanceSeconds = 60), TransactionSettingsStore.read(session))
        session.close()
    }

    @Test
    fun aRecordThatDoesNotParseIsKeptAndTheWriteRefused() {
        val session = open()
        val damaged = """{"hideSpam":"maybe","lastChanceSeconds":5}"""
        session.putVaultRecord(TransactionSettingsStore.RECORD, damaged.encodeToByteArray())

        assertEquals(TransactionSettings(), TransactionSettingsStore.read(session))
        val refusal = assertFailsWith<UnreadableRecordException> { TransactionSettingsStore.write(session, TransactionSettings(hideSpam = false)) }

        assertEquals("Your saved transaction settings could not be read, so nothing was changed.", refusal.message)
        assertEquals(damaged, session.getVaultRecord(TransactionSettingsStore.RECORD)!!.decodeToString())
        session.close()
    }

    @Test
    fun aWriteWithNoRecordSucceeds() {
        val session = open()

        TransactionSettingsStore.write(session, TransactionSettings(hideSpam = false))

        assertEquals(TransactionSettings(hideSpam = false), TransactionSettingsStore.read(session))
        session.close()
    }
}
