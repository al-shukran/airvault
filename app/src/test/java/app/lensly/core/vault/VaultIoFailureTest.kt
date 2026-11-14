package app.lensly.core.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A file that cannot be written or read during create or unlock must not throw out of either call. The read-only and unreadable files
 * below stand in for a failing store. Unlock still opens: the step that failed runs again on the next unlock.
 */
class VaultIoFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    /** Files and folders a test takes permissions away from. They are given them back after the test. */
    private val taken = mutableListOf<File>()

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @After
    fun giveBackPermissions() {
        taken.forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
    }

    @Test
    fun createReturnsDeviceUnsupportedWhenTheFirstDelayStateCannotBeSaved() {
        layout.root.mkdirs()
        taken += layout.root
        layout.root.setWritable(false)

        assertEquals(CreateResult.DeviceUnsupported, runBlocking { vault.create(APP.toCharArray()) })

        assertFalse(vault.exists())
    }

    @Test
    fun unlockOpensWhenAnUnusedFolderCannotBeBroughtToTheFolderShape() {
        create()
        val folder = layout.wallets.listFiles().orEmpty().first { it.isDirectory }
        File(folder, "secret.bin").delete()
        taken += folder
        folder.setWritable(false)

        assertTrue(unlockResult() is UnlockResult.Unlocked)
    }

    @Test
    fun unlockOpensWhenAnEarlierFreeSlotListCannotBeRead() {
        create()
        val pad = File(layout.root, WALLET_PAD_FILE_NAME)
        // An earlier build's list has another size, and it cannot be read.
        pad.writeBytes(ByteArray(100) { 1 })
        taken += pad
        pad.setReadable(false)

        assertTrue(unlockResult() is UnlockResult.Unlocked)
    }

    @Test
    fun unlockOpensWhenAnEarlierVaultRecordCannotBeRenamed() {
        create()
        val session = unlock()
        session.putVaultRecord("settings", byteArrayOf(1))
        session.close()
        // An earlier build named the record in plain text. Its keyed name is missing, so the move has somewhere to go.
        layout.vaultRecords.listFiles().orEmpty().filter { it.name != "settings.bin" }.forEach { it.delete() }
        File(layout.vaultRecords, "settings.bin").writeBytes(byteArrayOf(1, 2, 3))
        taken += layout.vaultRecords
        layout.vaultRecords.setWritable(false)

        assertTrue(unlockResult() is UnlockResult.Unlocked)
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(): VaultSession {
        val result = unlockResult()
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun unlockResult(): UnlockResult = runBlocking { vault.unlock(APP.toCharArray()) }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
