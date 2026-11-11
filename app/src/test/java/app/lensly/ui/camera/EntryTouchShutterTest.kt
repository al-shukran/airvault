package app.lensly.ui.camera

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The shutter click that ends a completed hold must take no photo and start no recording. */
class EntryTouchShutterTest {

    private fun shutterGestureTouch() = EntryTouch(slopPx = 8f, swipePx = 40f).apply {
        select(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)
    }

    @Test
    fun theClickThatEndsACompletedHoldIsIgnored() {
        val touch = shutterGestureTouch()
        var clicks = 0
        repeat(3) { touch.onModeSwitch(it * 1_000L) }

        // The pointer loop completes the hold on its timer while the finger is still down, and only then does the release click arrive.
        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 2_500L)
        assertTrue(touch.tick(3_300L))
        touch.completed()
        touch.onShutterClick { clicks++ }

        assertEquals(0, clicks)
    }

    @Test
    fun aShutterTapOnALaterTouchStillCounts() {
        val touch = shutterGestureTouch()
        var clicks = 0
        repeat(3) { touch.onModeSwitch(it * 1_000L) }
        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 2_500L)
        assertTrue(touch.tick(3_300L))
        touch.completed()
        touch.onShutterClick { clicks++ }

        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 4_000L)
        touch.onShutterClick { clicks++ }

        assertEquals(1, clicks)
    }
}
