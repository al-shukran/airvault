package app.lensly.ui.camera

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ThumbnailSwipeDetectorTest {

    /** Slop of 24 px, a 40 px leftward swipe, and a 600 ms long press. */
    private fun detector() = ThumbnailSwipeDetector(slopPx = 24f, swipePx = 40f, longPressMs = 600L)

    @Test
    fun aLongPressThenASwipeLeftCompletes() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        // Still within the slop while the long press runs.
        assertFalse(detector.onMove(302f, 901f, timeMs = 200))
        // Past the long press, the finger moves 50 px left, which is more than the 40 px swipe.
        assertTrue(detector.onMove(250f, 900f, timeMs = 700))
    }

    @Test
    fun aSwipeInStepsCompletesOnTheStepThatReachesTheDistance() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        assertFalse(detector.onMove(280f, 900f, timeMs = 650))
        // 30 px left is past the slop but short of the 40 px swipe.
        assertFalse(detector.onMove(270f, 900f, timeMs = 700))
        assertTrue(detector.onMove(255f, 900f, timeMs = 750))
    }

    @Test
    fun aCompletedSwipeDoesNotCompleteAgain() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        assertTrue(detector.onMove(250f, 900f, timeMs = 700))
        assertFalse(detector.onMove(200f, 900f, timeMs = 800))
    }

    @Test
    fun aQuickTapOnTheThumbnailDoesNotComplete() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        detector.cancel()
        assertFalse(detector.onMove(200f, 900f, timeMs = 120))
    }

    @Test
    fun aLongPressReleasedWithoutASwipeDoesNotComplete() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        assertFalse(detector.onMove(301f, 900f, timeMs = 700))
        detector.cancel()
        assertFalse(detector.onMove(200f, 900f, timeMs = 800))
    }

    @Test
    fun aSwipeBeforeTheLongPressIsADragAndDoesNotComplete() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        // The finger leaves the slop after 200 ms, which is too early for the long press.
        assertFalse(detector.onMove(250f, 900f, timeMs = 200))
        assertFalse(detector.onMove(200f, 900f, timeMs = 800))
    }

    @Test
    fun aRightwardMoveAfterTheLongPressCancelsTheGesture() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        assertFalse(detector.onMove(360f, 900f, timeMs = 700))
        assertFalse(detector.onMove(240f, 900f, timeMs = 750))
    }

    @Test
    fun aVerticalMoveAfterTheLongPressDoesNotCompleteASwipeLeft() {
        val detector = detector()

        detector.onDown(300f, 900f, timeMs = 0)
        // Mostly upward: the sideways distance is only 10 px.
        assertFalse(detector.onMove(290f, 800f, timeMs = 700))
        // Now 50 px left but 100 px up, so the move is not mostly sideways.
        assertFalse(detector.onMove(250f, 800f, timeMs = 750))
    }

    @Test
    fun aMoveWithoutAPressDoesNothing() {
        val detector = detector()

        assertFalse(detector.onMove(100f, 900f, timeMs = 1_000))
    }
}
