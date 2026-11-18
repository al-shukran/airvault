package app.lensly.ui.sign

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hold-to-sign control. A press must be held for the whole fill before it signs. Synthetic touches and the test clock
 * drive it, so the hold time is exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class HoldToSignTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var signed = 0

    private fun showControl(enabled: Boolean) {
        signed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme {
                HoldToSignButton(enabled = enabled, onSign = { signed++ })
            }
        }
        compose.waitForIdle()
    }

    private fun press() {
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).performTouchInput { down(center) }
    }

    private fun release() {
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).performTouchInput { up() }
    }

    /**
     * Sends a [action] event at the control's centre with [flags] set, the way a covering window marks the touches that pass
     * through it. Compose's own touch injection sets no window flags, so the event goes to the decor view directly.
     */
    private fun dispatchToWindow(action: Int, flags: Int) {
        val center = compose.onNodeWithTag(HOLD_TO_SIGN_TAG).fetchSemanticsNode().boundsInWindow.center
        val now = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = center.x
            y = center.y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            now, now, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        compose.activity.window.decorView.dispatchTouchEvent(event)
        event.recycle()
        // The pointer coroutine takes the event on the next idle, before the clock moves on.
        compose.waitForIdle()
    }

    @Test
    fun aPressLetGoBeforeTheFillIsFullSignsNothing() {
        showControl(enabled = true)

        press()
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS - 300L)
        release()
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS * 2L)

        assertEquals(0, signed)
    }

    @Test
    fun aFullHoldSignsOnceWhileTheFingerIsStillDown() {
        showControl(enabled = true)

        press()
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS + 100L)
        assertEquals(1, signed, "the hold signs when the fill is full, before the finger lifts")

        release()
        compose.mainClock.advanceTimeBy(1_000L)
        assertEquals(1, signed, "lifting the finger afterwards signs nothing more")
    }

    @Test
    fun aPressThroughAnObscuredWindowDoesNotStartTheHold() {
        showControl(enabled = true)

        dispatchToWindow(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS * 2L)

        assertEquals(0, signed)
    }

    @Test
    fun aPressThroughAPartlyObscuredWindowDoesNotStartTheHold() {
        showControl(enabled = true)

        dispatchToWindow(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS * 2L)

        assertEquals(0, signed)
    }

    @Test
    fun aPressThroughAnUnobscuredWindowStillSigns() {
        showControl(enabled = true)

        dispatchToWindow(MotionEvent.ACTION_DOWN, flags = 0)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS + 100L)

        assertEquals(1, signed, "the same raw path signs when nothing covers the window, so the obscured tests are not vacuous")
    }

    @Test
    fun aCoverThatAppearsMidPressCancelsTheHold() {
        showControl(enabled = true)

        // The press and the move both go through the decor view, which only routes a move to the press it delivered itself.
        dispatchToWindow(MotionEvent.ACTION_DOWN, flags = 0)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS - 300L)
        dispatchToWindow(MotionEvent.ACTION_MOVE, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS * 2L)

        assertEquals(0, signed, "an obscured move ends the hold, so the press signs nothing even once the fill would be full")
    }

    @Test
    fun aMoveThatIsNotObscuredLeavesTheHoldRunning() {
        showControl(enabled = true)

        dispatchToWindow(MotionEvent.ACTION_DOWN, flags = 0)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS - 300L)
        dispatchToWindow(MotionEvent.ACTION_MOVE, flags = 0)
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS * 2L)

        assertEquals(1, signed, "a move with no cover leaves the hold running, so the mid-press test is not vacuous")
    }

    @Test
    fun aDisabledControlTakesNoHoldAndSignsNothing() {
        showControl(enabled = false)
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        press()
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS + 100L)
        release()

        assertEquals(0, signed)
    }
}
