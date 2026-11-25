package app.lumio.ui.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.camera.CameraPermissionRationale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared camera cover's permission copy names the app that hosts it, which is Lumio here. */
@RunWith(RobolectricTestRunner::class)
class CameraPermissionCopyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun requestNamesLumio() {
        compose.setContent { CameraPermissionRationale(canOpenSettings = false, onAllow = {}, onOpenSettings = {}) }

        compose.onNodeWithText("Lumio needs the camera to take photos. Photos stay on this phone.").assertIsDisplayed()
    }

    @Test
    fun refusedNamesLumio() {
        compose.setContent { CameraPermissionRationale(canOpenSettings = true, onAllow = {}, onOpenSettings = {}) }

        compose.onNodeWithText("Camera access is off for Lumio. Turn it on in Settings, or allow it again here.").assertIsDisplayed()
    }
}
