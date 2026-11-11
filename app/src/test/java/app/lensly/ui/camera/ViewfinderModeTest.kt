package app.lensly.ui.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Photo and Video switch and the shutter's two jobs: a photo in photo mode, a recording in video mode. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ViewfinderModeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        mode: CameraMode,
        videoAvailable: Boolean = true,
        recording: Boolean = false,
        elapsedMs: Long = 0L,
        onModeChange: (CameraMode) -> Unit = {},
    ) {
        compose.setContent {
            LenslyTheme {
                ViewfinderOverlay(
                    flashAvailable = false,
                    flashOn = false,
                    onToggleFlash = {},
                    showCaptureFailure = false,
                    latestPhoto = null,
                    onOpenGallery = {},
                    onShutter = {},
                    onFlip = {},
                    mode = mode,
                    onModeChange = onModeChange,
                    videoAvailable = videoAvailable,
                    recording = recording,
                    elapsedMs = elapsedMs,
                )
            }
        }
    }

    @Test
    fun tappingVideoAsksForVideoMode() {
        var chosen: CameraMode? = null
        show(CameraMode.PHOTO, onModeChange = { chosen = it })

        compose.onNodeWithText("VIDEO").performClick()

        assertEquals(CameraMode.VIDEO, chosen)
    }

    @Test
    fun tappingPhotoAsksForPhotoMode() {
        var chosen: CameraMode? = null
        show(CameraMode.VIDEO, onModeChange = { chosen = it })

        compose.onNodeWithText("PHOTO").performClick()

        assertEquals(CameraMode.PHOTO, chosen)
    }

    @Test
    fun photoModeShutterIsTheTakePhotoControl() {
        show(CameraMode.PHOTO)

        compose.onNodeWithContentDescription("Take photo").assertIsDisplayed()
    }

    @Test
    fun videoModeShutterStartsARecording() {
        show(CameraMode.VIDEO)

        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test
    fun aRecordingShowsItsTimerAndAStopControl() {
        show(CameraMode.VIDEO, recording = true, elapsedMs = 7_400L)

        compose.onNodeWithText("0:07").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()
    }

    @Test
    fun aLensThatCannotRecordVideoOffersPhotoOnly() {
        show(CameraMode.PHOTO, videoAvailable = false)

        assertTrue(compose.onAllNodesWithText("VIDEO").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("PHOTO").assertIsDisplayed()
    }
}
