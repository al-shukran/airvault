package app.lensly.ui.sign

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The acknowledgement checkbox takes no tap that reaches it through a window that covers it, as the hold-to-sign control does. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class AckCheckboxTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var checked by mutableStateOf(false)

    private fun showCheckbox() {
        checked = false
        compose.setContent {
            LumenTheme {
                AckCheckbox(label = "I understand", checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag(TAG))
            }
        }
        compose.waitForIdle()
    }

    /** Taps the checkbox's centre through the decor view with [flags] set, the way a covering window marks the touches it lets through. */
    private fun tapThroughWindow(flags: Int) {
        val center = compose.onNodeWithTag(TAG).fetchSemanticsNode().boundsInWindow.center
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
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
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(1_000L)
    }

    @Test
    fun aTapThroughAnObscuredWindowDoesNotTick() {
        showCheckbox()
        tapThroughWindow(MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertFalse(checked)
    }

    @Test
    fun aTapThroughAPartlyObscuredWindowDoesNotTick() {
        showCheckbox()
        tapThroughWindow(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertFalse(checked)
    }

    @Test
    fun aTapThroughAnUnobscuredWindowTicks() {
        showCheckbox()
        tapThroughWindow(flags = 0)
        assertTrue(checked, "the same raw path ticks when nothing covers the window, so the obscured tests are not vacuous")
    }

    @Test
    fun aNormalClickTicks() {
        showCheckbox()
        compose.onNodeWithTag(TAG).performClick()
        assertTrue(checked)
    }

    private companion object {
        const val TAG = "ack-checkbox"
    }
}
