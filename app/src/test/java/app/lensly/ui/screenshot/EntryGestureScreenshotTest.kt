package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.settings.EntryGestureScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Hidden Entry Gesture choice: one selectable card for each gesture, the current one marked. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryGestureScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** 1 s into the loop: the double tap is in its hold, and the thumbnail gesture is still resting on the thumbnail. */
    @Test
    fun gestureChoice() = compose.snapshot("entry_gesture_choice", settleMs = 1_000L) {
        EntryGestureScreen(current = EntryGesture.DOUBLE_TAP_HOLD, onChoose = {}, onBack = {}, onLock = {})
    }
}
