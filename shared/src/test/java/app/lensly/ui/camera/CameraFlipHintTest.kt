package app.lensly.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The notice about the lens switch during a recording is part of the camera UI, not a system toast. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class CameraFlipHintTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showViewfinder(flipHint: Boolean) {
        compose.setContent {
            LenslyTheme {
                Box(Modifier.fillMaxSize()) {
                    ViewfinderOverlay(
                        flashAvailable = true,
                        flashOn = false,
                        onToggleFlash = {},
                        showCaptureFailure = false,
                        latestPhoto = null,
                        onOpenGallery = {},
                        onShutter = {},
                        onFlip = {},
                        flipHint = flipHint,
                    )
                }
            }
        }
    }

    @Test
    fun theHintShowsInsideTheCameraWhileRecording() {
        showViewfinder(flipHint = true)

        compose.onNodeWithText("Can't switch camera while recording").assertIsDisplayed()
    }

    @Test
    fun theHintIsNotShownOtherwise() {
        showViewfinder(flipHint = false)

        compose.onNodeWithText("Can't switch camera while recording").assertDoesNotExist()
    }
}
