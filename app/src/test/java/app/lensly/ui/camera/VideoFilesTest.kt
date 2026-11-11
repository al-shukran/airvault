package app.lensly.ui.camera

import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** A corrupt video file has no readable length, whatever the metadata retriever throws when it opens the file. */
@RunWith(RobolectricTestRunner::class)
class VideoFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aCorruptVideoHasNoLength() {
        val file = folder.newFile("VID_0.mp4").apply { writeBytes(ByteArray(32)) }
        // The retriever rejects a container it cannot parse with a runtime exception, not only an argument error.
        ShadowMediaMetadataRetriever.addException(
            DataSource.toDataSource(file.path),
            RuntimeException("not a video container"),
        )

        assertNull(videoDurationMs(file))
    }
}
