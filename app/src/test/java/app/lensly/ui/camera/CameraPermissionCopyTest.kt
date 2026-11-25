package app.lensly.ui.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared camera cover's permission copy names the app that hosts it, which is Lensly here. */
@RunWith(RobolectricTestRunner::class)
class CameraPermissionCopyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun requestNamesLensly() {
        compose.setContent { CameraPermissionRationale(canOpenSettings = false, onAllow = {}, onOpenSettings = {}) }

        compose.onNodeWithText("Lensly needs the camera to take photos. Photos stay on this phone.").assertIsDisplayed()
    }

    @Test
    fun refusedNamesLensly() {
        compose.setContent { CameraPermissionRationale(canOpenSettings = true, onAllow = {}, onOpenSettings = {}) }

        compose.onNodeWithText("Camera access is off for Lensly. Turn it on in Settings, or allow it again here.").assertIsDisplayed()
    }
}
