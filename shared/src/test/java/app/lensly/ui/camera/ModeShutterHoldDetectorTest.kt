package app.lensly.ui.camera

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ModeShutterHoldDetectorTest {

    /** Three camera switches, one second apart, ending at 2 000 ms. */
    private fun ModeShutterHoldDetector.threeSwitches() {
        onModeSwitch(0)
        onModeSwitch(1_000)
        onModeSwitch(2_000)
    }

    @Test
    fun threeSwitchesThenAHeldShutterCompletes() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onShutterDown(2_500)
        assertEquals(3_300L, detector.holdCompleteAtMs)
        assertFalse(detector.onTick(3_299))
        assertTrue(detector.onTick(3_300))
        // Completing once does not complete again.
        assertFalse(detector.onTick(3_400))
        assertNull(detector.holdCompleteAtMs)
    }

    @Test
    fun aHoldThatEndsAfterTheHoldTimeCompletesOnRelease() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onShutterDown(2_500)
        assertTrue(detector.onShutterUp(3_400))
    }

    @Test
    fun twoSwitchesDoNotArmTheShutter() {
        val detector = ModeShutterHoldDetector()

        detector.onModeSwitch(0)
        detector.onModeSwitch(1_000)
        detector.onShutterDown(2_500)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(4_000))
    }

    @Test
    fun aSwitchGapLongerThanTheWindowStartsTheCountAgain() {
        val detector = ModeShutterHoldDetector()

        detector.onModeSwitch(0)
        detector.onModeSwitch(1_000)
        // 4 s after the last switch, outside the 3 s window, so the count starts again at one.
        detector.onModeSwitch(5_000)
        detector.onShutterDown(5_500)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(7_000))
    }

    @Test
    fun aPressMoreThanTheWindowAfterTheSwitchesDoesNotCount() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        // The last switch was at 2 000 ms, so a press at 5 001 ms is 3 001 ms later, outside the window.
        detector.onShutterDown(5_001)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(7_000))
    }

    @Test
    fun aShortTapOnTheShutterUsesUpTheArming() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onShutterDown(2_500)
        assertFalse(detector.onShutterUp(2_600))
        // The arming was used by that press, so a later press does not start a hold.
        detector.onShutterDown(2_800)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(3_700))
    }

    @Test
    fun anOrdinaryShutterPressWithoutTheSwitchesDoesNothing() {
        val detector = ModeShutterHoldDetector()

        detector.onShutterDown(100)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(1_000))
        assertFalse(detector.onShutterUp(1_000))
    }

    @Test
    fun aTouchThatMovesCancelsTheHold() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onShutterDown(2_500)
        detector.cancel()
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(3_400))
    }

    @Test
    fun aCompletedGestureNeedsANewSequence() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onShutterDown(2_500)
        assertTrue(detector.onTick(3_300))
        detector.onShutterDown(4_000)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(5_000))
    }

    @Test
    fun aFourthSwitchBreaksTheArming() {
        val detector = ModeShutterHoldDetector()

        detector.threeSwitches()
        detector.onModeSwitch(2_500)
        detector.onShutterDown(2_800)
        assertNull(detector.holdCompleteAtMs)
        assertFalse(detector.onTick(4_000))
    }
}
