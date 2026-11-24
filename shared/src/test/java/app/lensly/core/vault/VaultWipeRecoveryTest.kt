package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A reset that fails half way. The erase is recorded before any key or file goes, so a failed erase leaves either the vault as it was,
 * or a vault that stays hidden until the next gate finishes the erase. No vault that exists but cannot be opened is left on the phone.
 */
class VaultWipeRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    /** Folders a test takes write access from. They get it back after the test, so the temporary folder can be removed. */
    private val denied = mutableListOf<File>()

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @After
    fun giveBackWriteAccess() {
        denied.forEach { it.setWritable(true) }
    }

    @Test
    fun aResetThatCannotStartLeavesTheVaultAsItWas() {
        createVault()
        deny(layout.root)

        assertFailsWith<VaultException> { vault.wipe() }

        giveBack(layout.root)
        assertTrue(vault.exists())
        unlocked(attempt(APP)).close()
    }

    @Test
    fun aResetThatStopsAfterItsKeysIsFinishedAtTheNextGate() {
        createVault()
        // A wallet folder that cannot be deleted stops the erase after the keys are gone.
        deny(layout.wallets)

        assertFailsWith<VaultException> { vault.wipe() }

        giveBack(layout.wallets)
        assertFalse(vault.exists())
        assertTrue(vault.isPartial())
        assertTrue(runBlocking { vault.finishPendingWipe() })
        assertFalse(layout.root.exists())
        assertTrue(vault.isBeforeSetup())
    }

    @Test
    fun aFinishThatFailsAgainKeepsTheVaultShut() {
        createVault()
        deny(layout.wallets)
        assertFailsWith<VaultException> { vault.wipe() }

        assertFalse(runBlocking { vault.finishPendingWipe() })

        assertFalse(vault.exists())
        assertTrue(vault.isPartial())
        assertRejected(attempt(APP))
        assertFailsWith<IllegalStateException> { runBlocking { vault.create(APP.toCharArray()) } }
        assertTrue(layout.wipeMarker.exists())
    }

    @Test
    fun aKeyThatCannotBeDeletedLeavesNoVaultAndNothingToFinish() {
        createVault()
        keys.failDeleteAll = true

        assertFailsWith<VaultException> { vault.wipe() }

        // Every file is gone, so the erase has nothing left to finish. The key that stayed is inert, and the next create replaces it.
        assertFalse(layout.root.exists())
        assertFalse(layout.wipeMarker.exists())
        assertTrue(vault.isBeforeSetup())
    }

    @Test
    fun aFinishWithNoErasePendingLeavesTheVaultAlone() {
        createVault()

        assertTrue(runBlocking { vault.finishPendingWipe() })

        unlocked(attempt(APP)).close()
    }

    @Test
    fun aProcessThatStoppedAfterItsKeysIsFinishedAndTheAppCanCreateAVaultAgain() {
        createVault()
        // The process stopped after its keys went and before any file did: the keys are gone, the files and the marker are still there.
        keys.deleteAll()
        layout.wipeMarker.writeBytes(ByteArray(0))
        val relaunched = Vault(layout, keys, clock, KDF)

        assertTrue(runBlocking { relaunched.finishPendingWipe() })

        assertEquals(CreateResult.Created, runBlocking { relaunched.create(APP.toCharArray()) })
    }

    private fun createVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    /** Takes the write access of [dir], so nothing directly inside it can be deleted. A root user can still write, and then the test is skipped. */
    private fun deny(dir: File) {
        denied += dir
        dir.setWritable(false)
        assumeFalse(dir.canWrite())
    }

    private fun giveBack(dir: File) {
        dir.setWritable(true)
    }

    /** Moves the clock past any failure delay, then tries [password]. */
    private fun attempt(password: String): UnlockResult {
        clock.advance(MAX_WAIT)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertTrue(result is UnlockResult.Rejected, "Expected Rejected but was $result")
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val MAX_WAIT = FailureDelay.MAX_CAP_MS + 1
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
