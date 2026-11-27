package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
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
    fun aShredStartedWhileAnotherShredOverwritesTheFileWaitsForIt() {
        val layout = withRecording()
        val overwriting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val startup = Thread { layout.shredRecording(overwrite = { overwriting.countDown(); release.await() }) }.apply { start() }
        assertTrue(overwriting.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // What the camera runs before it records into the same file.
        val recorder = Thread { layout.shredRecording() }.apply { start() }
        recorder.join(300)
        val recorderWaited = recorder.isAlive
        val untouched = layout.recording!!.exists()
        release.countDown()
        startup.join(5000)
        recorder.join(5000)

        assertTrue(recorderWaited)
        assertTrue(untouched)
        assertFalse(layout.recording!!.exists())
    }

    @Test
    fun aLateStartupShredLeavesARecordingThatBeganAfterAnotherShred() {
        val layout = withRecording()
        // The camera shreds before it records, then the recording begins.
        assertTrue(layout.shredRecording())
        layout.recording!!.writeBytes(ByteArray(64) { 3 })

        // The startup shred, delayed past all of that.
        assertTrue(layout.shredRecordingAtStartup())

        assertEquals(64, layout.recording!!.length())
    }

    @Test
    fun theStartupShredShredsTheLeftoverWhenNoOtherShredRan() {
        val layout = withRecording()

        assertTrue(layout.shredRecordingAtStartup())

        assertFalse(layout.recording!!.exists())
    }

    @Test
    fun aRecordingLargerThanTheBufferIsFullyOverwrittenThenDeleted() {
        val file = tmp.newFile("big.bin")
        val original = ByteArray(10 * 1024 + 3) { 5 }
        file.writeBytes(original)

        overwriteInPlace(file, chunkSize = 1024)

        val after = file.readBytes()
        assertEquals(original.size, after.size)
        // Every chunk is random: a chunk that kept its old bytes would still be all 5s.
        after.toList().chunked(1024).forEach { chunk -> assertFalse(chunk.all { it == 5.toByte() }) }
    }

    @Test
    fun aFailedOverwriteStillDeletesTheRecordingAndDoesNotThrow() {
        listOf<Throwable>(java.io.IOException("x"), IllegalStateException("x"), OutOfMemoryError("x")).forEach { failure ->
            val layout = withRecording()

            assertTrue(layout.shredRecording(overwrite = { throw failure }))

            assertFalse(layout.recording!!.exists())
        }
    }

    @Test
    fun theFolderOfTheDeletedRecordingIsSyncedSoThePowerCutCannotBringItBack() {
        val layout = withRecording()
        val synced = mutableListOf<File>()

        assertTrue(layout.shredRecording(sync = { synced += it }))

        assertEquals(listOf(layout.recording!!.parentFile), synced)
    }

    @Test
    fun aRecordingThatCannotBeDeletedIsReportedAndItsFolderIsNotSynced() {
        val layout = withRecording()
        layout.recording!!.delete()
        File(layout.recording, "in-the-way").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
        val synced = mutableListOf<File>()

        assertFalse(layout.shredRecording(sync = { synced += it }))

        assertTrue(layout.recording!!.exists())
        assertTrue(synced.isEmpty())
    }

    @Test
    fun aFolderThatCannotBeSyncedStillShredsTheRecording() {
        val layout = withRecording()

        assertTrue(layout.shredRecording(sync = { throw java.io.IOException("no sync") }))

        assertFalse(layout.recording!!.exists())
    }

    @Test
    fun aWipeWithARecordingThatCannotBeDeletedFailsAndKeepsTheEraseMarked() {
        val layout = withRecording()
        layout.recording!!.delete()
        File(layout.recording, "in-the-way").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))

        assertFailsWith<VaultException> { wipeVault(layout) {} }

        assertTrue(layout.wipeMarker.exists())
        assertTrue(layout.isPartial())
    }

    @Test
    fun theSweepOfALayoutWithNoRecordingFileDoesNothing() {
        assertTrue(populated().shredRecording())
    }
}
