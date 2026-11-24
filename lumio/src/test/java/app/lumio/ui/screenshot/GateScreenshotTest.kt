package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.gate.UnavailableScreen
import app.lensly.ui.gate.UnlockScreen
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.coverTheme
import app.lensly.ui.screenshot.snapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate that Lumio shows behind the camera cover: the unlock screen, idle and while the key is derived, and the unavailable screen. The
 * hidden area draws the shared gate in the cover's theme, so these goldens show the type Lumio's owner sees before the wallet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GateScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unlockIdle() = compose.snapshot("lumio_unlock_idle", theme = coverTheme) {
        UnlockScreen(length = 6, busy = false, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun unlockWhileKeyIsDerived() = compose.snapshot("lumio_unlock_unlocking", theme = coverTheme) {
        UnlockScreen(length = 0, busy = true, onKey = {}, onBackspace = {}, onSubmit = {})
    }

    @Test
    fun unavailable() = compose.snapshot("lumio_unavailable", theme = coverTheme) {
        UnavailableScreen(onClose = {})
    }
}
