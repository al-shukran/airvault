package app.lensly.ui.camera

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PinchTripleTapDetectorTest {

    /** A quick tap: the finger was down for 100 ms. */
    private fun PinchTripleTapDetector.quickTap(upAtMs: Long): Boolean = onTap(upAtMs, contactMs = 100L)

    @Test
    fun aPinchToTwoXThenThreeTapsCompletes() {
        val detector = PinchTripleTapDetector()

        detector.onPinchStart()
        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 1_000)
        assertFalse(detector.quickTap(upAtMs = 1_300))
        assertFalse(detector.quickTap(upAtMs = 1_600))
        assertTrue(detector.quickTap(upAtMs = 1_900))
    }

    @Test
    fun aZoomWithinTheToleranceOfTwoXArms() {
        val above = PinchTripleTapDetector()
        above.onPinchEnd(zoomRatio = 2.08f, timeMs = 0)
        assertFalse(above.quickTap(upAtMs = 300))
        assertFalse(above.quickTap(upAtMs = 600))
        assertTrue(above.quickTap(upAtMs = 900))

        val below = PinchTripleTapDetector()
        below.onPinchEnd(zoomRatio = 1.92f, timeMs = 0)
        assertFalse(below.quickTap(upAtMs = 300))
        assertFalse(below.quickTap(upAtMs = 600))
        assertTrue(below.quickTap(upAtMs = 900))
    }

    @Test
    fun aPinchEndingOutsideTheToleranceDoesNotArm() {
        for (zoom in listOf(2.2f, 1.7f, 1.0f, 5.0f)) {
            val detector = PinchTripleTapDetector()
            detector.onPinchEnd(zoomRatio = zoom, timeMs = 0)
            assertFalse(detector.quickTap(upAtMs = 300), "zoom $zoom")
            assertFalse(detector.quickTap(upAtMs = 600), "zoom $zoom")
            assertFalse(detector.quickTap(upAtMs = 900), "zoom $zoom")
        }
    }

    @Test
    fun ordinaryTapsWithoutThePinchNeverComplete() {
        val detector = PinchTripleTapDetector()

        for (tap in 1..6) {
            assertFalse(detector.quickTap(upAtMs = tap * 300L))
        }
    }

    @Test
    fun ordinaryTapsAfterAPinchAtAnotherZoomNeverComplete() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 1.0f, timeMs = 0)
        for (tap in 1..6) {
            assertFalse(detector.quickTap(upAtMs = tap * 300L))
        }
    }

    @Test
    fun aSlowPressIsNotATapAndRestartsTheRun() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        assertFalse(detector.quickTap(upAtMs = 300))
        // A press held for 500 ms breaks the run.
        assertFalse(detector.onTap(upAtMs = 600, contactMs = 500))
        assertFalse(detector.quickTap(upAtMs = 900))
        assertFalse(detector.quickTap(upAtMs = 1_200))
        assertTrue(detector.quickTap(upAtMs = 1_500))
    }

    @Test
    fun aGapLongerThanTheTapWindowRestartsTheRun() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        assertFalse(detector.quickTap(upAtMs = 300))
        assertFalse(detector.quickTap(upAtMs = 600))
        // The next tap comes 700 ms later, which is outside the 600 ms window, so the run starts again.
        assertFalse(detector.quickTap(upAtMs = 1_300))
        // Only two taps of the new run, so it does not complete.
        assertFalse(detector.quickTap(upAtMs = 1_600))
    }

    @Test
    fun anotherTouchBreaksTheRunOfTaps() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        assertFalse(detector.quickTap(upAtMs = 300))
        detector.onOtherTouch()
        assertFalse(detector.quickTap(upAtMs = 600))
        assertFalse(detector.quickTap(upAtMs = 900))
    }

    @Test
    fun theTapsMustComeWithinTheArmWindow() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        // The first tap is 10.001 s after the pinch, which is outside the 10 s window, and that disarms it.
        assertFalse(detector.quickTap(upAtMs = 10_001))
        assertFalse(detector.quickTap(upAtMs = 10_300))
        assertFalse(detector.quickTap(upAtMs = 10_600))
        assertFalse(detector.quickTap(upAtMs = 10_900))
    }

    @Test
    fun aNewPinchDisarmsTheGesture() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        detector.onPinchStart()
        assertFalse(detector.quickTap(upAtMs = 300))
        assertFalse(detector.quickTap(upAtMs = 600))
        assertFalse(detector.quickTap(upAtMs = 900))
    }

    @Test
    fun pinchingBackToTwoXArmsAgain() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        detector.onPinchStart()
        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 1_000)
        assertFalse(detector.quickTap(upAtMs = 1_300))
        assertFalse(detector.quickTap(upAtMs = 1_600))
        assertTrue(detector.quickTap(upAtMs = 1_900))
    }

    @Test
    fun aPinchEndingAwayFromTwoXDisarmsAnEarlierArming() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        detector.onPinchEnd(zoomRatio = 3.0f, timeMs = 500)
        assertFalse(detector.quickTap(upAtMs = 800))
        assertFalse(detector.quickTap(upAtMs = 1_100))
        assertFalse(detector.quickTap(upAtMs = 1_400))
    }

    @Test
    fun aCompletedGestureDoesNotCompleteAgainWithoutAnotherPinch() {
        val detector = PinchTripleTapDetector()

        detector.onPinchEnd(zoomRatio = 2.0f, timeMs = 0)
        assertFalse(detector.quickTap(upAtMs = 300))
        assertFalse(detector.quickTap(upAtMs = 600))
        assertTrue(detector.quickTap(upAtMs = 900))
        assertFalse(detector.quickTap(upAtMs = 1_200))
    }
}
