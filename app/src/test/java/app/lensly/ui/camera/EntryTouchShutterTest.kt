package app.lensly.ui.camera

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** The release event of a one-finger touch: no finger is down any more. */
    private fun release(timeMs: Long) = TouchFrame(
        count = 0,
        pressed = 1,
        position = null,
        timeMs = timeMs,
        moved = false,
        peak = 1,
        contactMs = timeMs - 2_500L,
    )

    // A completed hold never also takes a photo or starts a recording, whether the click that ends the touch reaches the
    // screen before the pointer loop sees the release or after it.

    @Test
    fun aHoldCompletedByTheReleaseSwallowsTheClickThatCameFirst() {
        val touch = shutterGestureTouch()
        var shutterActions = 0
        repeat(3) { touch.onModeSwitch(it * 1_000L) }
        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 2_500L)

        // The control's click lands before the pointer loop's release event.
        touch.onShutterClick { shutterActions++ }
        assertEquals(0, shutterActions)

        assertTrue(touch.activeEvent(release(3_400L)))
        assertEquals(0, shutterActions)
    }

    @Test
    fun aHoldCompletedByTheReleaseSwallowsTheClickThatComesAfter() {
        val touch = shutterGestureTouch()
        var shutterActions = 0
        repeat(3) { touch.onModeSwitch(it * 1_000L) }
        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 2_500L)

        assertTrue(touch.activeEvent(release(3_400L)))
        touch.onShutterClick { shutterActions++ }

        assertEquals(0, shutterActions)
    }

    @Test
    fun aQuickTapAfterTheSwitchesStillTakesItsPhoto() {
        val touch = shutterGestureTouch()
        var shutterActions = 0
        repeat(3) { touch.onModeSwitch(it * 1_000L) }
        touch.touchStarted(EntryRegion.SHUTTER, Offset.Zero, 2_500L)

        // The touch lifts before the hold time, so this is an ordinary shutter tap and its click counts.
        touch.onShutterClick { shutterActions++ }
        assertEquals(0, shutterActions)
        assertFalse(touch.activeEvent(release(2_600L)))

        assertEquals(1, shutterActions)
    }
}
