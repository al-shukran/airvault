package app.lensly.ui.camera

import androidx.camera.video.VideoRecordEvent
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A closed recording counts as saved only when its file is worth keeping. A source that goes inactive still keeps what it
 * wrote, but an empty or missing file is removed and reported as failed.
 */
class RecordingFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newFile(bytes: Int = 0): File =
        folder.newFile("VID_0.mp4").apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun aRecordingWithoutAnErrorIsSaved() {
        val file = newFile()

        assertTrue(recordingFileSaved(hasError = false, error = 0, file = file))
        assertTrue(file.exists())
    }

    @Test
    fun aRecordingCutShortByAnInactiveSourceIsKeptWhenItHasContent() {
        val file = newFile(bytes = 16)

        assertTrue(
            recordingFileSaved(
                hasError = true,
                error = VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                file = file,
            ),
        )
        assertTrue(file.exists())
        assertEquals(16L, file.length())
    }

    @Test
    fun anEmptyRecordingCutShortByAnInactiveSourceIsRemovedAndFailed() {
        val file = newFile()

        assertFalse(
            recordingFileSaved(
                hasError = true,
                error = VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                file = file,
            ),
        )
        assertFalse(file.exists())
    }

    @Test
    fun aRecordingCutShortByAnInactiveSourceWithNoFileFails() {
        val file = File(folder.root, "VID_missing.mp4")

        assertFalse(
            recordingFileSaved(
                hasError = true,
                error = VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                file = file,
            ),
        )
        assertFalse(file.exists())
    }

    @Test
    fun aRecordingThatFailedForAnotherReasonIsRemovedAndFailed() {
        val file = newFile(bytes = 16)

        assertFalse(
            recordingFileSaved(
                hasError = true,
                error = VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
                file = file,
            ),
        )
        assertFalse(file.exists())
    }
}
