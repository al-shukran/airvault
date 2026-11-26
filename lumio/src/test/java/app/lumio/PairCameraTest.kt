package app.lumio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import app.lensly.core.qr.QrDecodeService
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.FrameSourceHandle
import app.lensly.ui.qr.DECODE_FAILED_TEXT
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.ui.pair.PairChrome
import app.lumio.ui.pair.PairScanScreen
import app.lumio.ui.pair.PairWithLensly
import app.lumio.ui.scan.ReplyReading
import app.lumio.ui.scan.ReplyScanCard
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

    /** The bracket box keeps the mock's size, 196 by 192 dp, centred in the camera, on a 360 dp and a 411 dp phone: it never stretches with the card. */
    @Test
    fun theBracketBoxKeepsTheMockSizeAtEveryWidth() {
        compose.setContent {
            Theme {
                Column {
                    for (phone in listOf(PHONE_WIDTH, WIDE_PHONE_WIDTH)) {
                        Box(modifier = Modifier.width(phone).padding(horizontal = 20.dp)) {
                            SegmentedViewfinder(progress = null, complete = false, caption = "", modifier = Modifier.testTag("camera $phone")) { }
                        }
                    }
                }
            }
        }

        for (phone in listOf(PHONE_WIDTH, WIDE_PHONE_WIDTH)) {
            val node = compose.onNodeWithTag("camera $phone")
            val density = node.fetchSemanticsNode().layoutInfo.density.density
            val pixels = node.captureToImage().toPixelMap()
            // The paper brackets are the only light pixels over the dark backdrop between the rounded corners (26 dp) and the status line, which starts
            // 62 dp up from the foot.
            val rows = (30 * density).toInt() until (pixels.height - 62 * density).toInt()
            val light = rows.flatMap { y -> (0 until pixels.width).filter { x -> pixels[x, y].luminance() > 0.5f }.map { x -> x to y } }
            val width = (light.maxOf { it.first } - light.minOf { it.first } + 1) / density
            val height = (light.maxOf { it.second } - light.minOf { it.second } + 1) / density
            assertEquals(46f, light.minOf { it.second } / density, 1f, "bracket box top at $phone")
            val left = light.minOf { it.first } / density
            val right = pixels.width / density - (light.maxOf { it.first } + 1) / density
            assertEquals(196f, width, 1f, "bracket box width at $phone")
            assertEquals(192f, height, 1f, "bracket box height at $phone")
            assertEquals(left, right, 1f, "bracket box centring at $phone")
        }
    }

    /**
     * The status line stays readable over any picture: over a white camera, the line's paper text keeps the contrast of body text (4.5 to 1) against the
     * brightest pixel behind it.
     */
    @Test
    fun theStatusReadsOverAWhiteCamera() {
        compose.setContent {
            Theme {
                Box(modifier = Modifier.width(PHONE_WIDTH).padding(horizontal = 20.dp)) {
                    SegmentedViewfinder(progress = 0.6f, complete = false, caption = SCAN_HINT, modifier = Modifier.testTag("camera")) { viewport ->
                        Box(modifier = viewport.background(Color.White))
                    }
                }
            }
        }

        assertTrue(contrastOfStatus() >= 4.5f, "the status line over a white camera has a contrast of ${contrastOfStatus()}")
    }

    /** While the scanner cannot read, the pairing camera's one status line says so, and the scanner draws no notice over it. */
    @Test
    fun anUnreadableScannerSpeaksInTheStatusLine() {
        sandboxCannotBind()
        val oneFrame = FrameSource { emit ->
            emit(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            FrameSourceHandle { }
        }
        compose.setContent {
            Theme {
                PairScanScreen(chrome = PairChrome.Onboarding, progress = null, status = SCAN_HINT, onText = {}, frameSource = oneFrame)
            }
        }

        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText(DECODE_FAILED_TEXT).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(DECODE_FAILED_TEXT).assertCountEquals(1)
        compose.onAllNodesWithText(SCAN_HINT).assertCountEquals(0)
        compose.onNodeWithText(DECODE_FAILED_TEXT).assert(isPolite)
    }

    /** The reply card says the same in its words, in place of the parts read. */
    @Test
    fun anUnreadableScannerSpeaksInTheReplyCard() {
        sandboxCannotBind()
        val oneFrame = FrameSource { emit ->
            emit(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            FrameSourceHandle { }
        }
        compose.setContent {
            Theme {
                Box(modifier = Modifier.width(PHONE_WIDTH).padding(horizontal = 20.dp)) {
                    ReplyScanCard(reading = ReplyReading(received = 2, expected = 5), onText = {}, frameSource = oneFrame)
                }
            }
        }

        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText(DECODE_FAILED_TEXT).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(DECODE_FAILED_TEXT).assertCountEquals(1)
        compose.onAllNodesWithText("Reading Lensly's reply · 2 of 5").assertCountEquals(0)
        compose.onNodeWithText(DECODE_FAILED_TEXT).assert(isPolite)
    }

    /** The contrast of the status line's paper text, at its alpha over the brightest pixel in the line's rows, in the camera on the page. */
    private fun contrastOfStatus(): Float {
        val camera = compose.onNodeWithTag("camera")
        val density = camera.fetchSemanticsNode().layoutInfo.density.density
        val pixels = camera.captureToImage().toPixelMap()
        val text = compose.onNodeWithText(SCAN_HINT).getBoundsInRoot()
        val cameraBounds = camera.getBoundsInRoot()
        val rows = ((text.top - cameraBounds.top).value * density).toInt() until ((text.bottom - cameraBounds.top).value * density).toInt()
        // The status is centred, so the first 30 dp of each row are clear of its glyphs and of the brackets (62 dp in).
        val behind = rows.flatMap { y -> (0 until (30 * density).toInt()).map { x -> pixels[x, y] } }.maxBy { it.luminance() }
        val paper = PAPER.copy(alpha = 0.85f).compositeOver(behind)
        val light = maxOf(paper.luminance(), behind.luminance())
        val dark = minOf(paper.luminance(), behind.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    /** The QR Decode Sandbox cannot be bound, as when it has died: every frame is then unreadable. */
    private fun sandboxCannotBind() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).declareComponentUnbindable(ComponentName(context, QrDecodeService::class.java))
    }

    @Composable
    private fun Theme(content: @Composable () -> Unit) = LumenTheme { ClearAirTheme(content = content) }

    private companion object {
        /** A screen reader announces the text when it changes, as the old notice did. */
        val isPolite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        val PHONE_WIDTH = 360.dp
        val PHONE_HEIGHT = 780.dp
        val WIDE_PHONE_WIDTH = 411.dp
        val PAPER = Color(0xFFFBF6EC)
    }
}
