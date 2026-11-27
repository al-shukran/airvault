package app.lensly.ui.camera

import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun layout() = VaultLayout(folder.root, File(folder.root, "photos"))

    private fun jpeg(seed: Int): ByteArray = Random(seed).nextBytes(300)

    @Test
    fun aSavedPhotoOpensToTheBytesItWasSavedFrom() {
        val store = keyedStore(folder.root)
        val photo = jpeg(1)

        assertTrue(store.savePhoto(photo.copyOf(), nowMs = 1_000L))

        val saved = store.list().single()
        assertFalse(saved.isVideo)
        assertContentEquals(photo, store.photoBytes(saved.file))
    }

    @Test
    fun theFolderHoldsNoPlainPhoto() {
        val store = keyedStore(folder.root)
        val photo = jpeg(2)

        store.savePhoto(photo.copyOf(), nowMs = 1_000L)

        val files = store.directory.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertFalse(files.single().readBytes().holds(photo))
    }

    @Test
    fun aSavedVideoIsSealedAndItsRecordingIsShredded() {
        val key = MediaEnvelope.newPrivateKey()
        val store = keyedStore(folder.root, key)
        val bytes = Random(3).nextBytes(500)
        val recording = folder.newFile("recording.mp4").apply { writeBytes(bytes) }

        assertTrue(store.saveVideo(recording, nowMs = 2_000L))

        assertFalse(recording.exists())
        val video = store.list().single()
        assertTrue(video.isVideo)
        assertFalse(video.file.readBytes().holds(bytes))
        assertContentEquals(bytes, store.photoBytes(video.file))
    }

    @Test
    fun listShowsPhotosAndVideosNewestFirst() {
        val store = keyedStore(folder.root)
        store.savePhoto(jpeg(4), nowMs = 1_000L)
        store.savePhoto(jpeg(5), nowMs = 2_000L)
        store.saveVideo(folder.newFile("recording.mp4").apply { writeBytes(jpeg(6)) }, nowMs = 3_000L)

        val listed = store.list()

        assertEquals(3, listed.size)
        assertTrue(listed[0].isVideo)
        assertEquals(3_000L, listed[0].capturedMs)
        assertContentEquals(jpeg(5), store.photoBytes(listed[1].file))
        assertContentEquals(jpeg(4), store.photoBytes(listed[2].file))
    }

    @Test
    fun listLeavesOutFilesThatAreNotSealedMedia() {
        val store = keyedStore(folder.root)
        store.savePhoto(jpeg(7), nowMs = 1_000L)
        // A plain photo, which is not sealed, and a stray file.
        File(store.directory, "IMG_0.jpg").writeBytes(jpeg(8))
        File(store.directory, "notes.txt").writeBytes(ByteArray(1))

        assertEquals(1, store.list().size)
    }

    @Test
    fun aPhotoIsNotSavedBeforeTheVaultHasAKey() {
        // No key file yet: the vault has not been created or unlocked.
        val store = PhotoStore.of(layout(), decoy = false)

        assertFalse(store.savePhoto(jpeg(9), nowMs = 1_000L))

        assertTrue(store.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theCoversStoreSavesButOpensNothing() {
        keyedStore(folder.root)
        // The cover's store holds no private key, so it can save a photo and cannot open one.
        val cover = PhotoStore.of(layout(), decoy = false)

        assertTrue(cover.savePhoto(jpeg(10), nowMs = 1_000L))

        assertTrue(cover.list().isEmpty())
        assertNull(cover.photoBytes(cover.directory.listFiles().orEmpty().single()))
    }

    @Test
    fun aClosedStoreOpensNoPhoto() {
        val store = keyedStore(folder.root)
        store.savePhoto(jpeg(11), nowMs = 1_000L)
        val saved = store.list().single()

        store.close()

        assertNull(store.photoBytes(saved.file))
    }

    @Test
    fun deleteRemovesThePhoto() {
        val store = keyedStore(folder.root)
        store.savePhoto(jpeg(12), nowMs = 1_000L)

        assertTrue(store.delete(store.list().single().file))

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun aRecordingStartsOnlyAfterTheScratchFileIsShredded() {
        val order = mutableListOf<String>()
        val caller = Thread.currentThread()
        var shredThread: Thread? = null

        val started = runBlocking {
            shredThenStart(
                shred = { shredThread = Thread.currentThread(); order += "shred"; true },
                shredContext = Dispatchers.IO,
                start = { order += "start"; true },
            )
        }

        assertTrue(started)
        assertEquals(listOf("shred", "start"), order)
        assertTrue(shredThread !== caller)
    }

    @Test
    fun aRecordingDoesNotStartWhenTheLeftoverCannotBeDeleted() {
        var startCalled = false

        val started = runBlocking {
            shredThenStart(shred = { false }, shredContext = Dispatchers.IO, start = { startCalled = true; true })
        }

        assertFalse(started)
        assertFalse(startCalled)
    }
}
