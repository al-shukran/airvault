package app.lensly.ui.qr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.FrameSourceHandle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the production default of the [QrScanner] seam. With no frame source, and camera permission missing, the scanner
 * shows the camera permission prompt, which is the path it took before the seam existed. Nothing of the fixture decoder
 * is reachable without a source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrScannerSeamTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun withoutAFrameSourceTheScannerKeepsTheCameraPermissionGate() {
        compose.setContent {
            LumenTheme {
                QrScanner(onText = {})
            }
        }

        compose.onNodeWithText("Camera Access").assertExists()
        compose.onNodeWithText("Lensly reads QR codes with the camera. Images never leave the phone.").assertExists()
    }

    @Test
    fun theViewfinderOverlayCanBeLeftOut() {
        val idle = FrameSource { FrameSourceHandle { } }
        compose.setContent {
            LumenTheme {
                Column {
                    QrScanner(onText = {}, modifier = Modifier.size(200.dp).testTag("with"), frameSource = idle)
                    QrScanner(onText = {}, modifier = Modifier.size(200.dp).testTag("without"), frameSource = idle, viewfinder = false)
                }
            }
        }

        // The scrim dims the corner when the overlay is drawn. Without it the scanner draws nothing there.
        assertNotEquals(cornerPixel("without"), cornerPixel("with"))
    }

    private fun cornerPixel(tag: String): Int = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[2, 2].hashCode()
}
