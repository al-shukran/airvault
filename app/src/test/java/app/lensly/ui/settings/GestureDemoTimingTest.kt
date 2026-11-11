package app.lensly.ui.settings

import androidx.compose.ui.geometry.Offset
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.camera.HiddenGestureDetector
import app.lensly.ui.camera.ModeShutterHoldDetector
import app.lensly.ui.camera.PinchTripleTapDetector
import app.lensly.ui.camera.ThumbnailSwipeDetector
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Each demo must make its gesture as the detectors in ui/camera read it: the same taps, holds and swipe length, and the
 * completion at the moment the demo shows it. The card is 347 px wide and 195 px high, and the detectors take pixels.
 */
class GestureDemoTimingTest {

    private fun pixels(fraction: Offset) = Offset(fraction.x * CARD_WIDTH, fraction.y * CARD_HEIGHT)

    @Test
    fun doubleTapThenHoldCompletesWhileBothFingersAreStillDown() {
        val script = gestureScript(EntryGesture.DOUBLE_TAP_HOLD)
        val detector = HiddenGestureDetector()
        var pressed = 0
        var completedAt: Long? = null
        for (event in pointerEvents(script)) {
            detector.holdCompleteAtMs?.let { deadline ->
                if (deadline <= event.ms && detector.onTick(deadline)) completedAt = deadline
            }
            val completed = if (event.down) {
                pressed++
                detector.onDown(pressed, event.ms.toLong())
            } else {
                pressed--
                detector.onUp(pressed, event.ms.toLong())
            }
            assertFalse(completed, "a lift at ${event.ms} ms completed the gesture before the hold")
        }
        assertEquals(script.completeMs.toLong(), completedAt)
        assertTrue(script.completeMs < script.fingers.last().upMs, "the hold completes while still held")
    }

    @Test
    fun thumbnailHoldThenSwipeCompletesAsTheFingerReachesTheSwipeLength() {
        val script = gestureScript(EntryGesture.THUMBNAIL_HOLD_SWIPE)
        val finger = script.fingers.single()
        val detector = ThumbnailSwipeDetector(slopPx = 8f, swipePx = SWIPE_PX)
        val landed = pixels(finger.at(finger.downMs))
        detector.onDown(landed.x, landed.y, finger.downMs.toLong())
        var completedAt: Int? = null
        for (ms in finger.downMs + 1 until finger.upMs) {
            val at = pixels(finger.at(ms))
            if (detector.onMove(at.x, at.y, ms.toLong())) {
                completedAt = ms
                break
            }
        }
        val done = assertNotNull(completedAt, "the swipe must complete")
        assertTrue(done in (script.completeMs - 25)..script.completeMs, "the swipe completed at $done ms")
    }

    @Test
    fun pinchToTwoThenThreeTapsArmsAndCompletesWithTheLastTap() {
        val script = gestureScript(EntryGesture.PINCH_TRIPLE_TAP)
        val pinch = script.fingers.filter { it.downMs < TAPS_FROM_MS }
        val taps = script.fingers.filter { it.downMs >= TAPS_FROM_MS }
        val detector = PinchTripleTapDetector()
        detector.onPinchStart()
        val spanAtStart = span(pinch, 250)
        val spanAtEnd = span(pinch, 1_050)
        detector.onPinchEnd(zoomRatio = spanAtEnd / spanAtStart, timeMs = 1_150L)
        var completedAt: Int? = null
        for (tap in taps) {
            if (detector.onTap(upAtMs = tap.upMs.toLong(), contactMs = (tap.upMs - tap.downMs).toLong())) {
                completedAt = tap.upMs
            }
        }
        assertEquals(script.completeMs, completedAt)
    }

    @Test
    fun threeSwitchTapsThenAHeldShutterCompletesWhileStillDown() {
        val script = gestureScript(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)
        val switches = script.fingers.filter { it.downMs < TAPS_FROM_MS }
        val shutter = script.fingers.single { it.downMs >= TAPS_FROM_MS }
        val detector = ModeShutterHoldDetector()
        for (tap in switches) detector.onModeSwitch(tap.upMs.toLong())
        detector.onShutterDown(shutter.downMs.toLong())
        assertEquals(script.completeMs.toLong(), detector.holdCompleteAtMs)
        assertFalse(detector.onTick((script.completeMs - 1).toLong()))
        assertTrue(detector.onTick(script.completeMs.toLong()))
        assertTrue(script.completeMs < shutter.upMs, "the hold completes while still held")
    }

    /** Distance in pixels between the first two fingers of [fingers] at [ms]. */
    private fun span(fingers: List<DemoFinger>, ms: Int): Float =
        (pixels(fingers[0].at(ms)) - pixels(fingers[1].at(ms))).getDistance()

    /** Every landing and lifting of [script], in time order. A lift comes before a landing at the same instant. */
    private fun pointerEvents(script: GestureScript): List<PointerEvent> = script.fingers
        .flatMap { listOf(PointerEvent(it.downMs, down = true), PointerEvent(it.upMs, down = false)) }
        .sortedWith(compareBy<PointerEvent> { it.ms }.thenBy { if (it.down) 1 else 0 })

    private class PointerEvent(val ms: Int, val down: Boolean)

    private companion object {
        const val CARD_WIDTH = 347f
        const val CARD_HEIGHT = 195f
        const val SWIPE_PX = 40f
        const val TAPS_FROM_MS = 1_300
    }
}
