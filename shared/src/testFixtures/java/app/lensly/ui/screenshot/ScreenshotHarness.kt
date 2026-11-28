package app.lensly.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LocalVaultLock
import app.lensly.ui.theme.CoverTheme
import app.lensly.ui.theme.LumenTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * Robolectric window for screenshot tests: 720 x 1600 dp at mdpi, so the window is 720 x 1600 px. Put it on a test class
 * with `@Config(qualifiers = SCREENSHOT_QUALIFIERS)`. [snapshot] lays content out at the phone's density inside it.
 */
const val SCREENSHOT_QUALIFIERS = "w720dp-h1600dp-port-mdpi"

/** The same window in the night theme, for a test that records a night golden with `@Config(qualifiers = ...)` on its method. */
const val SCREENSHOT_NIGHT_QUALIFIERS = "w720dp-h1600dp-port-night-mdpi"

/**
 * Where the goldens are written. Relative to the app module, which is the test working directory. `SCREENSHOT_OUT` names another folder, which
 * scripts/design/render-at-density.sh uses to render at a measuring density without touching the goldens.
 */
val SCREENSHOT_DIR: String = System.getenv("SCREENSHOT_OUT") ?: "src/test/screenshots"

/** The share of pixels allowed to differ from a golden: 0.05 percent, about 576 pixels of a 720 x 1600 frame. */
private const val SCREENSHOT_CHANGE_THRESHOLD = 0.0005f

/** The owner's phone: 720 x 1600 px at 280 dpi, which is density 1.75. */
private const val SCREEN_WIDTH_PX = 720f
private const val SCREEN_HEIGHT_PX = 1600f
const val SCREEN_DENSITY = 1.75f

/** The density a frame is drawn at: the phone's, unless `SCREENSHOT_DENSITY` names another for measuring (1 px = 1 dp, or 2). */
private val RENDER_DENSITY: Float = System.getenv("SCREENSHOT_DENSITY")?.toFloatOrNull() ?: SCREEN_DENSITY

/** The tag of the frame, so a check can read its pixels and its semantics tree. */
const val SCREEN_TAG = "lensly-screenshot-screen"

/**
 * Renders [content] in [theme], which is the Lumen theme unless a screen names another, such as [coverTheme] for the camera cover and the gate, at 720 x 1600 px and 280 dpi, then records it
 * as [SCREENSHOT_DIR]/[name].png. Call it once per test, in one line. The frame is sized in dp so its pixel size is exact. [settleMs]
 * advances the paused clock before the capture, so an entrance animation has finished.
 */
/** The camera cover and the gate keep the cover theme: Lensly's original look. */
val coverTheme: @Composable (content: @Composable () -> Unit) -> Unit = { CoverTheme(content = it) }

fun ComposeContentTestRule.snapshot(
    name: String,
    settleMs: Long = 0L,
    beforeCapture: ComposeContentTestRule.() -> Unit = {},
    theme: @Composable (content: @Composable () -> Unit) -> Unit = { LumenTheme(content = it) },
    frame: DpSize? = null,
    fontScale: Float = 1f,
    density: Float = RENDER_DENSITY,
    content: @Composable () -> Unit,
) {
    val size = frame ?: DpSize((SCREEN_WIDTH_PX / SCREEN_DENSITY).dp, (SCREEN_HEIGHT_PX / SCREEN_DENSITY).dp)
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            theme {
                Box(
                    modifier = Modifier
                        .testTag(SCREEN_TAG)
                        .requiredSize(width = size.width, height = size.height)
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    // Every golden is of the unlocked vault, so a detail top bar shows its lock icon.
                    CompositionLocalProvider(LocalVaultLock provides {}) { content() }
                }
            }
        }
    }
    waitForIdle()
    mainClock.advanceTimeBy(settleMs)
    waitForIdle()
    // [beforeCapture] runs once the entrance has settled, for a step such as a scroll, and the frame is then drawn again.
    beforeCapture()
    waitForIdle()
    // The goldens are recorded on the Linux CI runner (the record-goldens workflow), the runner the nightly verifies on. A macOS
    // render differs on anti-aliased edges, so a local macOS run is advisory. The macOS and Linux renders of one screen differed by at
    // most 2 of 255 per channel on 35 to 62 of a frame's 1,152,000 pixels. A real change moves far more pixels than the threshold.
    onNodeWithTag(SCREEN_TAG).captureRoboImage(
        filePath = "$SCREENSHOT_DIR/$name.png",
        roborazziOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = SCREENSHOT_CHANGE_THRESHOLD),
        ),
    )
}
