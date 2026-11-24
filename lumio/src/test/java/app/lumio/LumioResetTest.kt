package app.lumio

import app.lensly.app.EntryGestureStore
import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FailureCapResult
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.AutoLock
import app.lumio.data.config.LockSettings
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
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
    private val layout by lazy { VaultLayout(File(tmp.root, "data"), File(tmp.root, "photos")) }
    private val receiveShares by lazy { File(tmp.root, "cache/receive") }
    // What LumioApp closes as its database handle. A test makes it throw to show the stores go all the same.
    private var closeDatabase: () -> Unit = {}
    // The erase after a wrong try runs what LumioApp runs: the session locks, and the database files, the shared receive code and the lock
    // choices go, as on Reset Lumio.
    private val vault: Vault by lazy {
        Vault(layout, MemoryKeystore(), clock, KDF, onErased = {
            eraseAfterVaultErase(holder, closeDatabase, lumioDatabaseFile(layout), receiveShares, LockSettingsStore.open(context))
        })
    }
    private val holder: VaultHolder by lazy { VaultHolder(vault) }

    @Test
    fun theDatabaseSitsInsideTheVaultFolderSoAnyEraseRemovesIt() {
        assertEquals(layout.root, lumioDatabaseFile(layout).parentFile)
    }

    @Test
    fun aWrongPasswordEraseRemovesTheDatabaseAndTheLockChoices() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        assertEquals(FailureCapResult.Saved, vault.setFailureLimits(PASSWORD.toCharArray(), null, WIPE_AFTER))
        val databaseFile = lumioDatabaseFile(layout).apply { parentFile!!.mkdirs() }
        LumioDatabase.open(context, databaseFile).close()
        assertTrue(databaseFile.isFile)
        LockSettingsStore.open(context).save(LockSettings(AutoLock.IMMEDIATELY))
        shareReceiveCode()
        // The wrong tries come from an open session, as Change Password's check of the current password does.
        open()

        repeat(WIPE_AFTER - 1) { assertEquals(UnlockResult.Rejected, wrongTry()) }
        assertEquals(UnlockResult.Erased, wrongTry())

        assertNull(holder.openSession(), "the session outlived the erase")
        assertFalse(vault.exists())
        assertFalse(databaseFile.exists())
        assertFalse(receiveShares.exists(), "the shared receive code outlived the erase")
        assertTrue(context.getSharedPreferences(LockSettingsStore.FILE, 0).all.isEmpty(), "the lock choices outlived the erase")
        assertEquals(LockSettings(), LockSettingsStore.open(context).load())
    }

    @Test
    fun aWrongPasswordEraseRemovesTheOutsideStoresWhenTheDatabaseCloseThrows() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        assertEquals(FailureCapResult.Saved, vault.setFailureLimits(PASSWORD.toCharArray(), null, WIPE_AFTER))
        LockSettingsStore.open(context).save(LockSettings(AutoLock.IMMEDIATELY))
        shareReceiveCode()
        closeDatabase = { throw IllegalStateException("close failed") }

        repeat(WIPE_AFTER - 1) { assertEquals(UnlockResult.Rejected, wrongTry()) }
        assertFailsWith<IllegalStateException> { wrongTry() }

        assertFalse(vault.exists())
        assertFalse(receiveShares.exists())
        assertTrue(context.getSharedPreferences(LockSettingsStore.FILE, 0).all.isEmpty())
    }

    @Test
    fun resetErasesEveryLumioStoreAndKeepsTheCameraGesture() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        open()
        holder.openSession()!!.putVaultRecord(PROBE, PROBE_VALUE.toByteArray())
        val databaseFile = lumioDatabaseFile(layout).apply { parentFile!!.mkdirs() }
        LumioDatabase.open(context, databaseFile).close()
        val lock = LockSettingsStore.open(context).also { it.save(LockSettings(AutoLock.AFTER_FIVE_MINUTES)) }
        val gestures = EntryGestureStore.open(context).also { it.save(EntryGesture.PINCH_TRIPLE_TAP) }
        shareReceiveCode()
        assertTrue(databaseFile.isFile)

        // The caller closes the database first, as LumioApp does.
        eraseLumioStores(holder, databaseFile, receiveShares, lock)

        assertNull(holder.openSession())
        assertFalse(vault.exists())
        assertFalse(File(tmp.root, "data").exists())
        assertFalse(databaseFile.exists())
        assertFalse(receiveShares.exists())
        assertTrue(context.getSharedPreferences(LockSettingsStore.FILE, 0).all.isEmpty())
        assertEquals(LockSettings(), lock.load())
        assertEquals(EntryGesture.PINCH_TRIPLE_TAP, gestures.load())
    }

    @Test
    fun aResetThatStopsHalfWayErasesTheDatabaseAndTheNextGateFinishesTheVault() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        open()
        val databaseFile = lumioDatabaseFile(layout).apply { parentFile!!.mkdirs() }
        LumioDatabase.open(context, databaseFile).close()
        val lock = LockSettingsStore.open(context).also { it.save(LockSettings(AutoLock.AFTER_FIVE_MINUTES)) }
        // A wallet folder that cannot be deleted stops the erase after the keys are gone.
        layout.wallets.setWritable(false)
        try {
            assumeFalse(layout.wallets.canWrite())
            assertFailsWith<VaultException> { eraseLumioStores(holder, databaseFile, receiveShares, lock) }
        } finally {
            layout.wallets.setWritable(true)
        }

        assertFalse(databaseFile.exists())
        assertEquals(LockSettings(), lock.load())
        assertTrue(vault.finishPendingWipe())
        assertFalse(File(tmp.root, "data").exists())
    }

    @Test
    fun aResetWhoseProcessDiedBeforeTheOutsideStoresIsFinishedWithThemAtTheNextGate() = runBlocking {
        assertEquals(CreateResult.Created, vault.create(PASSWORD.toCharArray()))
        LockSettingsStore.open(context).save(LockSettings(AutoLock.AFTER_FIVE_MINUTES))
        shareReceiveCode()
        // The process died inside the vault's erase, after its marker was written and before the lock choices and the receive code went.
        layout.wipeMarker.writeBytes(ByteArray(0))

        assertTrue(vault.finishPendingWipe())

        assertFalse(File(tmp.root, "data").exists())
        assertFalse(receiveShares.exists(), "the shared receive code outlived the reset")
        assertTrue(context.getSharedPreferences(LockSettingsStore.FILE, 0).all.isEmpty(), "the lock choices outlived the reset")
    }

    /** A receive code shared from Lumio, as the Receive sheet leaves it in the app's cache. */
    private fun shareReceiveCode() {
        File(receiveShares.apply { mkdirs() }, "receive-qr.png").writeBytes(byteArrayOf(1, 2, 3))
    }

    /** A wrong password, tried after the longest failure delay, so the delay does not refuse the try before it is counted. */
    private suspend fun wrongTry(): UnlockResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return vault.unlock(WRONG.toCharArray())
    }

    /** Opens the vault with [PASSWORD] and keeps the session in the holder, as the gate does. */
    private suspend fun open() {
        val result = vault.unlock(PASSWORD.toCharArray())
        assertIs<UnlockResult.Unlocked>(result)
        holder.adopt(result.session, holder.unlockEpoch())
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val WIPE_AFTER = 5
        const val PROBE = "lumio-reset-probe"
        const val PROBE_VALUE = "wiped"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
