package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.LumioVault
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The "Prefer RPC over Explorers" choice as the open vault holds it: a screen that outlives the vault can still read it. */
@RunWith(RobolectricTestRunner::class)
class PreferRpcVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 13 + 7).toByte() })

    private fun database(): LumioDatabase = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "prefer-rpc.db"))

    @Test
    fun theChoiceIsSavedAndReadBackAfterTheVaultReopens() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        assertFalse(vault.preferRpc())

        vault.savePreferRpc(true)

        assertTrue(PreferRpcStore.read(session))
        database.close()
        session.close()

        val reopenedSession = open()
        val reopenedDatabase = database()
        assertTrue(LumioVault(reopenedSession, reopenedDatabase).preferRpc())
        reopenedDatabase.close()
        reopenedSession.close()
    }

    @Test
    fun theChoiceIsStillReadAfterTheVaultCloses() {
        val session = open()
        val database = database()
        val vault = LumioVault(session, database)
        vault.savePreferRpc(true)
        session.close()

        assertTrue(vault.preferRpc())
        assertFailsWith<IllegalStateException> { vault.savePreferRpc(false) }
        assertTrue(vault.preferRpc())
        database.close()
    }
}
