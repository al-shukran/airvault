package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.LumioVault
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The transaction settings as the open vault publishes them: a change is saved, then published, and a failed save publishes nothing. */
@RunWith(RobolectricTestRunner::class)
class TransactionSettingsFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 3 + 5).toByte() })

    private fun database(): LumioDatabase = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "transactions.db"))

    @Test
    fun aChangeIsPublishedAndReadBackAfterTheVaultReopens() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        assertEquals(TransactionSettings(), vault.transactions.value)

        vault.updateTransactions { it.copy(hideSpam = false, lastChanceSeconds = 0) }

        assertEquals(vault.transactions.value, TransactionSettingsStore.read(session))
        database.close()
        session.close()

        val reopenedSession = open()
        val reopenedDatabase = database()
        val reopened = LumioVault(reopenedSession, reopenedDatabase)
        assertEquals(TransactionSettings(hideSpam = false, lastChanceSeconds = 0), reopened.transactions.value)
        reopenedDatabase.close()
        reopenedSession.close()
    }

    @Test
    fun aFailedSaveKeepsThePublishedSettings() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        vault.updateTransactions { it.copy(lastChanceSeconds = 5) }
        session.close()

        assertFailsWith<IllegalStateException> { vault.updateTransactions { it.copy(lastChanceSeconds = 9) } }

        assertEquals(5, vault.transactions.value.lastChanceSeconds)
        database.close()
    }
}
