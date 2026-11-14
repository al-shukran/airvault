package app.lensly.ui.qr

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the production default of the [QrScanner] seam. With no frame source, and camera permission missing, the scanner
 * shows the camera permission prompt, which is the path it took before the seam existed. Nothing of the fixture decoder
 * is reachable without a source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrScannerSeamTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun withoutAFrameSourceTheScannerKeepsTheCameraPermissionGate() {
        compose.setContent {
            LenslyTheme {
                QrScanner(onText = {})
            }
        }

        compose.onNodeWithText("CAMERA ACCESS").assertExists()
        compose.onNodeWithText("Lensly reads QR codes with the camera. Images never leave the phone.").assertExists()
    }
}
