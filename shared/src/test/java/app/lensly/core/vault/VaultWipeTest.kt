package app.lensly.core.vault

import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A vault with every kind of file it writes: header, wrapped key, failure state, a wallet, and a vault record. Its photos are there
     * too, one in each vault's folder.
     */
    private fun populated(): VaultLayout {
        val layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        layout.root.mkdirs()
        layout.header.writeBytes(byteArrayOf(1, 2, 3))
        layout.vaultKey.writeBytes(byteArrayOf(4, 5, 6))
        File(layout.root, "failures.bin").writeBytes(byteArrayOf(7))
        File(layout.root, "failures.bak").writeBytes(byteArrayOf(8))
        val session = VaultSession(layout, MemoryKeystore(), ByteArray(32) { 9 })
        val wallet = session.createWallet()
        session.putRecord(wallet, "secret", byteArrayOf(10))
        session.putVaultRecord("settings", byteArrayOf(11))
        session.close()
        layout.photoDir(decoy = false).mkdirs()
        File(layout.photoDir(decoy = false), "IMG_1.jpg").writeBytes(byteArrayOf(12))
        layout.photoDir(decoy = true).mkdirs()
        File(layout.photoDir(decoy = true), "IMG_2.jpg").writeBytes(byteArrayOf(13))
        return layout
    }

    @Test
    fun aWipeLeavesNoVaultFiles() {
        val layout = populated()
        assertTrue(layout.exists())

        wipeVault(layout) {}

        assertFalse(layout.exists())
        assertFalse(layout.root.exists())
        assertTrue(tmp.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun aWipeRemovesThePhotosOfBothVaults() {
        val layout = populated()

        wipeVault(layout) {}

        assertFalse(layout.photoDir(decoy = false).exists())
        assertFalse(layout.photoDir(decoy = true).exists())
        assertFalse(layout.photos.exists())
    }

    @Test
    fun aKeyDeleteFailureStillRemovesEveryFileAndIsThrown() {
        val layout = populated()

        assertFailsWith<VaultException> { wipeVault(layout) { throw VaultException("The Keystore refused to delete the keys.") } }

        assertFalse(layout.exists())
        assertFalse(layout.photos.exists())
    }

    @Test
    fun theKeysGoBeforeAnyFile() {
        val layout = populated()
        var vaultStillThereWhenKeysWentAway = false

        // exists() is false once the erase has begun, so the header stands for the vault's files.
        wipeVault(layout) { vaultStillThereWhenKeysWentAway = layout.header.exists() }

        assertTrue(vaultStillThereWhenKeysWentAway)
        assertFalse(layout.root.exists())
    }

    private fun withRecording(): VaultLayout {
        val layout = populated().let { VaultLayout(it.root, it.photos, File(tmp.root, "cache/recording.mp4")) }
        layout.recording!!.parentFile.mkdirs()
        layout.recording!!.writeBytes(ByteArray(64) { 7 })
        return layout
    }

    @Test
    fun aWipeShredsAPlainVideoThatAnInterruptedRecordingLeft() {
        val layout = withRecording()

        wipeVault(layout) {}

        assertFalse(layout.recording!!.exists())
    }

    @Test
    fun theStartupSweepShredsTheLeftoverVideoAndKeepsTheSealedMedia() {
        val layout = withRecording()
        val sealed = File(layout.photoDir(decoy = false), "IMG_1.jpg")

        assertTrue(layout.shredRecording())

        assertFalse(layout.recording!!.exists())
        assertTrue(sealed.exists())
        assertTrue(layout.exists())
    }

    @Test
    fun theSweepOfALayoutWithNoRecordingFileDoesNothing() {
        assertTrue(populated().shredRecording())
    }
}
