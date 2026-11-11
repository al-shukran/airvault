package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.camera.CameraPermissionRationale
import app.lensly.ui.camera.CameraUnavailable
import app.lensly.ui.camera.GalleryScreen
import app.lensly.ui.camera.ViewfinderOverlay
import app.lensly.ui.theme.LenslySurfaceContainer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Camera group states. The live preview cannot render off the device, so the viewfinder controls sit over a flat
 * placeholder, and the gallery is shown with no photos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permissionRequest() = compose.snapshot("camera_permission_request") {
        CameraPermissionRationale(canOpenSettings = false, onAllow = {}, onOpenSettings = {})
    }

    @Test
    fun permissionRefused() = compose.snapshot("camera_permission_refused") {
        CameraPermissionRationale(canOpenSettings = true, onAllow = {}, onOpenSettings = {})
    }

    @Test
    fun unavailable() = compose.snapshot("camera_unavailable") {
        CameraUnavailable(onRetry = {}, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun viewfinderControls() = compose.snapshot("camera_viewfinder_controls") {
        Box(Modifier.fillMaxSize().background(LenslySurfaceContainer)) {
            ViewfinderOverlay(
                flashAvailable = true,
                flashOn = false,
                onToggleFlash = {},
                showCaptureFailure = false,
                latestPhoto = null,
                onOpenGallery = {},
                onShutter = {},
                onFlip = {},
            )
        }
    }

    @Test
    fun captureFailed() = compose.snapshot("camera_capture_failed") {
        Box(Modifier.fillMaxSize().background(LenslySurfaceContainer)) {
            ViewfinderOverlay(
                flashAvailable = true,
                flashOn = true,
                onToggleFlash = {},
                showCaptureFailure = true,
                latestPhoto = null,
                onOpenGallery = {},
                onShutter = {},
                onFlip = {},
            )
        }
    }

    @Test
    fun galleryEmpty() = compose.snapshot("gallery_empty") {
        GalleryScreen(onBack = {})
    }
}
