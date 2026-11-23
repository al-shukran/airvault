package app.lumio

import app.lensly.app.EntryGestureStore
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.LockSettings
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Reset Lumio erases every store Lumio keeps on this phone: the App Password vault with its keys, the chain data database and the lock choices.
 * The camera's Hidden Entry Gesture survives, so the cover does not change.
 */
@RunWith(RobolectricTestRunner::class)
class LumioResetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val clock = FakeClock()
    // Lazy, because the rule creates the temporary folder only after the test instance is built.
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "data"), File(tmp.root, "photos")), MemoryKeystore(), clock, KDF) }
    private val holder by lazy { VaultHolder(vault) }

    @Test
    fun resetErasesEveryLumioStoreAndKeepsTheCameraGesture() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        open()
        holder.openSession()!!.putVaultRecord(PROBE, PROBE_VALUE.toByteArray())
        val databaseFile = File(tmp.root, "vault/${LumioDatabase.FILE_NAME}").apply { parentFile!!.mkdirs() }
        LumioDatabase.open(context, databaseFile).close()
        val lock = LockSettingsStore.open(context).also { it.save(LockSettings(idleMinutes = 5, lockWhenSwitchingApps = true)) }
        val gestures = EntryGestureStore.open(context).also { it.save(EntryGesture.PINCH_TRIPLE_TAP) }
        assertTrue(databaseFile.isFile)

        // The caller closes the database first, as LumioApp does.
        eraseLumioStores(holder, databaseFile, lock)

        assertNull(holder.openSession())
        assertFalse(vault.exists())
        assertFalse(File(tmp.root, "data").exists())
        assertFalse(databaseFile.exists())
        assertTrue(context.getSharedPreferences(LockSettingsStore.FILE, 0).all.isEmpty())
        assertEquals(LockSettings(), lock.load())
        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, gestures.load())
    }

    /** Opens the vault with [PASSWORD] and keeps the session in the holder, as the gate does. */
    private suspend fun open() {
        val result = vault.unlock(PASSWORD.toCharArray())
        assertIs<UnlockResult.Unlocked>(result)
        holder.adopt(result.session, holder.unlockEpoch())
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val PROBE = "lumio-reset-probe"
        const val PROBE_VALUE = "wiped"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
