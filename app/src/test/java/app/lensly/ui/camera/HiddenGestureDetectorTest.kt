package app.lensly.ui.camera

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HiddenGestureDetectorTest {

    /** Two fingers land at [startMs] and both lift at [endMs]. Returns true if the gesture completed. */
    private fun HiddenGestureDetector.twoFingerTap(startMs: Long, endMs: Long): Boolean {
        onDown(1, startMs)
        onDown(2, startMs)
        val completed = onUp(1, endMs)
        val lastLift = onUp(0, endMs)
        return completed || lastLift
    }

    /** Two fingers land at [startMs] and stay down. Nothing is released yet. */
    private fun HiddenGestureDetector.twoFingersDown(startMs: Long): Boolean {
        onDown(1, startMs)
        return onDown(2, startMs)
    }

    /** Both fingers lift at [atMs]. */
    private fun HiddenGestureDetector.liftBoth(atMs: Long): Boolean {
        val completed = onUp(1, atMs)
        onUp(0, atMs)
        return completed
    }

    /** A single finger taps at [startMs] for [heldMs]. */
    private fun HiddenGestureDetector.singleFingerTap(startMs: Long, heldMs: Long) {
        onDown(1, startMs)
        onUp(0, startMs + heldMs)
    }

    @Test
    fun correctSequenceTriggersAfterHoldElapses() {
        val detector = HiddenGestureDetector()

        assertFalse(detector.twoFingerTap(startMs = 0, endMs = 100))
        // Second tap starts 150 ms after the first release, inside the 400 ms window.
        assertFalse(detector.twoFingerTap(startMs = 250, endMs = 350))
        // Hold starts 150 ms after the second release.
        assertFalse(detector.twoFingersDown(startMs = 500))
        assertEquals(1_700L, detector.holdCompleteAtMs)

        assertFalse(detector.onTick(timeMs = 1_699))
        assertTrue(detector.onTick(timeMs = 1_700))
        // Completing once does not complete again.
        assertFalse(detector.onTick(timeMs = 1_800))
        assertNull(detector.holdCompleteAtMs)
    }

    @Test
    fun holdReleasedAfterThresholdTriggersOnLift() {
        val detector = HiddenGestureDetector()

        detector.twoFingerTap(startMs = 0, endMs = 100)
        detector.twoFingerTap(startMs = 250, endMs = 350)
        detector.twoFingersDown(startMs = 500)

        // The timer never fired, but the fingers lift 1.3 s after the hold started.
        assertTrue(detector.liftBoth(atMs = 1_800))
    }

    @Test
    fun tooSlowDoubleTapDoesNotTrigger() {
        val detector = HiddenGestureDetector()

        assertFalse(detector.twoFingerTap(startMs = 0, endMs = 100))
        // The second tap starts 500 ms after the first release, outside the 400 ms window.
        // It restarts the sequence instead of completing the double tap.
        assertFalse(detector.twoFingersDown(startMs = 600))
        assertFalse(detector.onTick(timeMs = 600 + 1_500))
        assertFalse(detector.liftBoth(atMs = 2_100))
    }

    @Test
    fun slowSecondTapFollowedByHoldDoesNotTrigger() {
        val detector = HiddenGestureDetector()

        assertFalse(detector.twoFingerTap(startMs = 0, endMs = 100))
        // Slow second tap restarts the sequence as a first tap, so the hold that follows
        // is only the second tap of that pair and is far too long to count as a tap.
        assertFalse(detector.twoFingerTap(startMs = 600, endMs = 700))
        assertFalse(detector.twoFingersDown(startMs = 800))
        assertFalse(detector.onTick(timeMs = 800 + 1_500))
        assertFalse(detector.liftBoth(atMs = 2_400))
    }

    @Test
    fun releaseBeforeHoldCompletesDoesNotTrigger() {
        val detector = HiddenGestureDetector()

        detector.twoFingerTap(startMs = 0, endMs = 100)
        detector.twoFingerTap(startMs = 250, endMs = 350)
        detector.twoFingersDown(startMs = 500)

        // Released after 600 ms of holding, which is less than 1.2 s.
        assertFalse(detector.liftBoth(atMs = 1_100))
        assertFalse(detector.onTick(timeMs = 3_000))
        assertFalse(detector.liftBoth(atMs = 3_100))
    }

    @Test
    fun singleFingerTapsDoNotTrigger() {
        val detector = HiddenGestureDetector()

        repeat(10) { index ->
            val start = index * 300L
            detector.singleFingerTap(startMs = start, heldMs = 80)
        }
        assertFalse(detector.onTick(timeMs = 10_000))
        assertNull(detector.holdCompleteAtMs)
    }

    @Test
    fun singleFingerTapBetweenTwoFingerTapsBreaksSequence() {
        val detector = HiddenGestureDetector()

        assertFalse(detector.twoFingerTap(startMs = 0, endMs = 100))
        detector.singleFingerTap(startMs = 150, heldMs = 60)
        assertFalse(detector.twoFingerTap(startMs = 250, endMs = 350))
        assertFalse(detector.twoFingersDown(startMs = 500))
        assertFalse(detector.onTick(timeMs = 1_700))
        assertFalse(detector.liftBoth(atMs = 1_800))
    }

    @Test
    fun singleFingerHoldDoesNotTrigger() {
        val detector = HiddenGestureDetector()

        onSingleFingerHold(detector, heldMs = 3_000)
        assertFalse(detector.onTick(timeMs = 3_000))
        assertNull(detector.holdCompleteAtMs)
    }

    @Test
    fun pinchCancelsAHoldInProgress() {
        val detector = HiddenGestureDetector()

        detector.twoFingerTap(startMs = 0, endMs = 100)
        detector.twoFingerTap(startMs = 250, endMs = 350)
        detector.twoFingersDown(startMs = 500)
        detector.cancel()

        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(timeMs = 2_000))
        assertFalse(detector.liftBoth(atMs = 2_100))
    }

    @Test
    fun thirdFingerResetsSequence() {
        val detector = HiddenGestureDetector()

        detector.twoFingerTap(startMs = 0, endMs = 100)
        detector.twoFingerTap(startMs = 250, endMs = 350)
        detector.twoFingersDown(startMs = 500)
        assertFalse(detector.onDown(3, timeMs = 600))
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(timeMs = 2_000))
    }

    @Test
    fun pressLongerThanTapMaxIsNotATap() {
        val detector = HiddenGestureDetector()

        // A 500 ms press is too long to be the first tap.
        assertFalse(detector.twoFingerTap(startMs = 0, endMs = 500))
        // This is therefore the first tap, and the next press is only the second tap.
        assertFalse(detector.twoFingerTap(startMs = 550, endMs = 650))
        assertFalse(detector.twoFingersDown(startMs = 700))
        assertFalse(detector.onTick(timeMs = 2_000))
    }

    @Test
    fun holdDeadlineIsContactStartPlusHoldDuration() {
        val detector = HiddenGestureDetector()

        detector.twoFingerTap(startMs = 0, endMs = 100)
        detector.twoFingerTap(startMs = 250, endMs = 350)
        assertNull(detector.holdCompleteAtMs)
        detector.twoFingersDown(startMs = 500)
        assertEquals(500L + 1_200L, detector.holdCompleteAtMs)
    }

    private fun onSingleFingerHold(detector: HiddenGestureDetector, heldMs: Long) {
        detector.onDown(1, 0)
        detector.onUp(0, heldMs)
    }
}
