package app.lensly.ui.qr

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import app.lensly.core.qr.QrDecodeService
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
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

    /** A caller that draws its own frame is told when the sandbox cannot answer, and the scanner draws no notice of its own over that frame. */
    @Test
    fun aCallerWithItsOwnFrameHearsOfAFailureInsteadOfTheNotice() {
        sandboxCannotBind()
        val oneFrame = FrameSource { emit ->
            emit(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            FrameSourceHandle { }
        }
        var failed by mutableStateOf(false)
        compose.setContent {
            LumenTheme {
                QrScanner(onText = {}, frameSource = oneFrame, viewfinder = false, onDecodeFailed = { failed = it })
            }
        }

        compose.waitUntil(timeoutMillis = 30_000) { failed }
        compose.onAllNodesWithText(DECODE_FAILED_TEXT).assertCountEquals(0)
    }

    /** The scanner with its own viewfinder keeps saying so itself. */
    @Test
    fun aScannerWithItsOwnViewfinderStillShowsTheNotice() {
        sandboxCannotBind()
        val oneFrame = FrameSource { emit ->
            emit(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            FrameSourceHandle { }
        }
        compose.setContent {
            LumenTheme {
                QrScanner(onText = {}, frameSource = oneFrame)
            }
        }

        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText(DECODE_FAILED_TEXT).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The QR Decode Sandbox cannot be bound, as when it has died: every frame is then unreadable. */
    private fun sandboxCannotBind() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).declareComponentUnbindable(ComponentName(context, QrDecodeService::class.java))
    }

    private fun cornerPixel(tag: String): Int = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[2, 2].hashCode()
}
