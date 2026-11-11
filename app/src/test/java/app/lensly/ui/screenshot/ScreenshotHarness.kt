package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * Robolectric window for screenshot tests: 720 x 1600 dp at mdpi, so the window is 720 x 1600 px. Put it on a test class
 * with `@Config(qualifiers = SCREENSHOT_QUALIFIERS)`. [snapshot] lays content out at the phone's density inside it.
 */
const val SCREENSHOT_QUALIFIERS = "w720dp-h1600dp-port-mdpi"

/** Where the goldens are written. Relative to the app module, which is the test working directory. */
const val SCREENSHOT_DIR = "src/test/screenshots"

/** The owner's phone: 720 x 1600 px at 280 dpi, which is density 1.75. */
private const val SCREEN_WIDTH_PX = 720f
private const val SCREEN_HEIGHT_PX = 1600f
private const val SCREEN_DENSITY = 1.75f
private const val SCREEN_TAG = "lensly-screenshot-screen"

/**
 * Renders [content] in the Lensly dark theme at 720 x 1600 px and 280 dpi, then records it as [SCREENSHOT_DIR]/[name].png.
 * Call it once per test, in one line. The frame is sized in dp so its pixel size is exact. [settleMs] advances the paused clock
 * before the capture, so an entrance animation has finished.
 */
fun ComposeContentTestRule.snapshot(name: String, settleMs: Long = 0L, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(SCREEN_DENSITY)) {
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .testTag(SCREEN_TAG)
                        .requiredSize(
                            width = (SCREEN_WIDTH_PX / SCREEN_DENSITY).dp,
                            height = (SCREEN_HEIGHT_PX / SCREEN_DENSITY).dp,
                        )
                        .background(LenslyBlack),
                ) {
                    content()
                }
            }
        }
    }
    waitForIdle()
    mainClock.advanceTimeBy(settleMs)
    waitForIdle()
    onNodeWithTag(SCREEN_TAG).captureRoboImage(filePath = "$SCREENSHOT_DIR/$name.png")
}
