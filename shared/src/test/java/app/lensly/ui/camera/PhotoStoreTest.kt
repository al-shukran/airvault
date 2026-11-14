package app.lensly.ui.camera

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = PhotoStore(folder.root.resolve("photos"))

    private fun File.write(): File = apply { writeBytes(ByteArray(1)) }

    @Test
    fun aVideoIsAnMp4InTheSameFolderAsThePhotos() {
        val store = store()

        val video = store.newVideoFile(nowMs = 0L)

        assertEquals(store.directory, video.parentFile)
        assertTrue(video.name.startsWith("VID_"))
        assertEquals("mp4", video.extension)
        assertEquals("jpg", store.newFile(nowMs = 0L).extension)
    }

    @Test
    fun listShowsPhotosAndVideosNewestFirst() {
        val store = store()
        val oldPhoto = store.newFile(nowMs = 1_000L).write()
        val newVideo = store.newVideoFile(nowMs = 3_000L).write()
        val newPhoto = store.newFile(nowMs = 2_000L).write()

        assertEquals(listOf(newVideo, newPhoto, oldPhoto), store.list())
    }

    @Test
    fun listLeavesOutFilesThatAreNeitherPhotosNorVideos() {
        val store = store()
        val photo = store.newFile(nowMs = 1_000L).write()
        File(store.directory, "notes.txt").write()

        assertEquals(listOf(photo), store.list())
    }
}
