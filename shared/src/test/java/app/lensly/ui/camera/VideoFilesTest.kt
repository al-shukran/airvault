package app.lensly.ui.camera

import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

/** A corrupt video has no readable length, whatever the metadata retriever throws when it opens the video. */
@RunWith(RobolectricTestRunner::class)
class VideoFilesTest {

    @Test
    fun aCorruptVideoHasNoLength() {
        val source = stubMediaSource()
        // The retriever rejects a container it cannot parse with a runtime exception, not only an argument error.
        ShadowMediaMetadataRetriever.addException(
            DataSource.toDataSource(source),
            RuntimeException("not a video container"),
        )

        assertNull(videoDurationMs(source))
    }
}
