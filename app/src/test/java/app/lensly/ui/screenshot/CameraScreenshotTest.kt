package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.camera.CameraPermissionRationale
import app.lensly.ui.camera.CameraUnavailable
import app.lensly.ui.camera.GalleryScreen
import app.lensly.ui.camera.PhotoStore
import app.lensly.ui.camera.ViewfinderOverlay
import app.lensly.ui.components.LocalVaultLock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    fun permissionRequest() = compose.snapshot("camera_permission_request", theme = coverTheme) {
        CameraPermissionRationale(canOpenSettings = false, onAllow = {}, onOpenSettings = {})
    }

    @Test
    fun permissionRefused() = compose.snapshot("camera_permission_refused", theme = coverTheme) {
        CameraPermissionRationale(canOpenSettings = true, onAllow = {}, onOpenSettings = {})
    }

    @Test
    fun unavailable() = compose.snapshot("camera_unavailable", theme = coverTheme) {
        CameraUnavailable(onRetry = {}, modifier = Modifier.fillMaxSize())
    }

    @Test
    fun viewfinderControls() = compose.snapshot("camera_viewfinder_controls", theme = coverTheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            ViewfinderOverlay(
                flashAvailable = true,
                flashOn = false,
                onToggleFlash = {},
                showCaptureFailure = false,
                onOpenGallery = {},
                onShutter = {},
                onFlip = {},
            )
        }
    }

    @Test
    fun captureFailed() = compose.snapshot("camera_capture_failed", theme = coverTheme) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            ViewfinderOverlay(
                flashAvailable = true,
                flashOn = true,
                onToggleFlash = {},
                showCaptureFailure = true,
                onOpenGallery = {},
                onShutter = {},
                onFlip = {},
            )
        }
    }

    @Test
    fun galleryEmpty() = compose.snapshot("gallery_empty", theme = coverTheme) {
        // The gallery opens from the camera while the vault is locked, so it shows no lock icon.
        CompositionLocalProvider(LocalVaultLock provides null) {
            GalleryScreen(store = PhotoStore.of(VaultLayout.of(RuntimeEnvironment.getApplication()), decoy = false), onBack = {})
        }
    }
}
