package app.lumio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.FrameSourceHandle
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.ui.pair.PairChrome
import app.lumio.ui.pair.PairScanScreen
import app.lumio.ui.pair.PairWithLensly
import app.lumio.ui.scan.SegmentedViewfinder
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The camera of the Pair with Lensly page, as the locked mock draws it on a 360 dp phone: four corner brackets and nothing else over the camera, and the
 * status on one line under the brackets and over the bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairCameraTest {

    @get:Rule
    val compose = createComposeRule()

    /** The scanner on the page draws no ring, scrim or backdrop of its own: the page looks the same as with no scanner at all. */
    @Test
    fun theScannerDrawsNothingOverThePairingFrame() {
        val idle = FrameSource { FrameSourceHandle { } }
        compose.setContent {
            Theme {
                Row {
                    Box(modifier = Modifier.size(PHONE_WIDTH, PHONE_HEIGHT).testTag("scanner")) {
                        PairScanScreen(chrome = PairChrome.Onboarding, progress = null, status = SCAN_HINT, onText = {}, frameSource = idle)
                    }
                    Box(modifier = Modifier.size(PHONE_WIDTH, PHONE_HEIGHT).testTag("bare")) {
                        PairWithLensly(
                            chrome = PairChrome.Onboarding,
                            progress = null,
                            status = SCAN_HINT,
                            complete = false,
                            denied = false,
                            onOpenSettings = {},
                        ) { }
                    }
                }
            }
        }

        val scanner = compose.onNodeWithTag("scanner").captureToImage().toPixelMap()
        val bare = compose.onNodeWithTag("bare").captureToImage().toPixelMap()
        var differing = 0
        for (x in 0 until scanner.width) for (y in 0 until scanner.height) if (scanner[x, y] != bare[x, y]) differing++
        assertEquals(0, differing, "pixels the scanner draws over the pairing frame")
    }

    /** Every status the scan shows fits one line between the bottom of the bracket box and the top of the bar, in a 360 dp phone's camera. */
    @Test
    fun everyStatusIsOneLineUnderTheBrackets() {
        var status by mutableStateOf(SCAN_HINT)
        compose.setContent {
            Theme {
                Box(modifier = Modifier.width(PHONE_WIDTH).padding(horizontal = 20.dp)) {
                    SegmentedViewfinder(progress = 0.6f, complete = false, caption = status, modifier = Modifier.testTag("camera")) { }
                }
            }
        }

        for (each in listOf(SCAN_HINT, readingStatus(12, 20), readingStatus(120, 120), SCAN_READING, SCAN_STALLED)) {
            status = each
            compose.waitForIdle()
            val camera = compose.onNodeWithTag("camera").getBoundsInRoot()
            val text = compose.onNodeWithText(each).getBoundsInRoot()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(each).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()

            assertEquals(1, layout.lineCount, "\"$each\" wraps")
            assertFalse(layout.isLineEllipsized(0), "\"$each\" is cut short")
            // The mock's bracket box ends 62 dp above the camera's foot, and its bar is 4 dp thick, 20 dp above the foot.
            assertTrue(text.top >= camera.bottom - 62.dp, "\"$each\" overlaps the brackets: ${text.top} against ${camera.bottom - 62.dp}")
            assertTrue(text.bottom <= camera.bottom - 24.dp, "\"$each\" overlaps the bar: ${text.bottom} against ${camera.bottom - 24.dp}")
        }
    }

    @Composable
    private fun Theme(content: @Composable () -> Unit) = LumenTheme { ClearAirTheme(content = content) }

    private companion object {
        val PHONE_WIDTH = 360.dp
        val PHONE_HEIGHT = 780.dp
    }
}
