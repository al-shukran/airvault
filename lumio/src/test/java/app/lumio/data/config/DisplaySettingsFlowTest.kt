package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.LumioVault
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The display settings as the open vault publishes them: a change is saved, then published, and a failed save publishes nothing. */
@RunWith(RobolectricTestRunner::class)
class DisplaySettingsFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 13 + 7).toByte() })

    private fun database(): LumioDatabase = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "display.db"))

    @Test
    fun aChangeIsPublishedAndReadBackAfterTheVaultReopens() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        assertEquals(DisplaySettings(), vault.display.value)

        vault.updateDisplay { it.copy(showTotal = true, sort = AssetSort(AssetSortKey.NAME, descending = false)) }

        assertEquals(vault.display.value, DisplaySettingsStore.read(session))
        database.close()
        session.close()

        val reopenedSession = open()
        val reopenedDatabase = database()
        val reopened = LumioVault(reopenedSession, reopenedDatabase)
        assertTrue(reopened.display.value.showTotal)
        assertEquals(AssetSort(AssetSortKey.NAME, descending = false), reopened.display.value.sort)
        reopenedDatabase.close()
        reopenedSession.close()
    }

    @Test
    fun aFailedSaveKeepsThePublishedSettings() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        vault.updateDisplay { it.copy(hideSpam = false) }
        session.close()

        assertFailsWith<IllegalStateException> { vault.updateDisplay { it.copy(hideSpam = true) } }

        assertFalse(vault.display.value.hideSpam)
        database.close()
    }
}
