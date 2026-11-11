package app.lensly.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PAD_TAG = "entry-gesture-pad"

/**
 * The practice surface runs the camera's pointer code, so these tests drive it with synthetic touches. Each gesture completes
 * on its own sequence, and an ordinary tap, pinch or press does not complete one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class EntryGesturePadTest {

    @get:Rule
    val compose = createComposeRule()

    private var performed = 0
    private var shutterClicks = 0

    private fun showPad(gesture: EntryGesture) {
        performed = 0
        shutterClicks = 0
        compose.setContent {
            LenslyTheme {
                Box(Modifier.testTag(PAD_TAG).fillMaxSize()) {
                    EntryGesturePad(
                        gesture = gesture,
                        onPerformed = { performed++ },
                        onShutterClick = { shutterClicks++ },
                    )
                }
            }
        }
    }

    private fun padTouch(block: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(PAD_TAG).performTouchInput(block)
    }

    /** Two fingers land and lift together, quickly, at the centre of the viewfinder. */
    private fun TouchInjectionScope.twoFingerTap() {
        down(0, Offset(center.x - 30f, center.y))
        down(1, Offset(center.x + 30f, center.y))
        advanceEventTime(100)
        up(0)
        up(1)
    }

    /** One quick tap at the centre: the finger is down for one event period, well under the tap limit. */
    private fun TouchInjectionScope.tap() {
        down(center)
        up()
    }

    /** A pinch that doubles the span between two fingers, so the zoom goes to 2.0x, and lifts. */
    private fun TouchInjectionScope.pinchToTwoX() {
        down(0, Offset(center.x - 100f, center.y))
        down(1, Offset(center.x + 100f, center.y))
        moveTo(1, Offset(center.x + 300f, center.y))
        up(1)
        up(0)
        advanceEventTime(300)
    }

    /** Two double taps with two fingers, then both fingers held for the hold time. */
    private fun TouchInjectionScope.doubleTapThenHold() {
        twoFingerTap()
        advanceEventTime(150)
        twoFingerTap()
        advanceEventTime(150)
        down(0, Offset(center.x - 30f, center.y))
        down(1, Offset(center.x + 30f, center.y))
        advanceEventTime(1_300)
        up(0)
        up(1)
    }

    /** Switches to Video with the mode switch. The gestures must still work once it is set. */
    private fun enterVideoMode() {
        compose.onNodeWithText("VIDEO").performClick()
    }

    /** Three camera switches, then the shutter held for the hold time. */
    private fun threeSwitchesThenHoldShutter(shutterLabel: String) {
        repeat(3) { compose.onNodeWithContentDescription("Switch camera").performClick() }
        compose.onNodeWithContentDescription(shutterLabel).performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }
    }

    @Test
    fun twoFingerDoubleTapThenHoldCompletesTheDefaultGesture() {
        showPad(EntryGesture.DOUBLE_TAP_HOLD)

        padTouch { doubleTapThenHold() }

        assertEquals(1, performed)
    }

    @Test
    fun aTwoFingerTapWithoutTheHoldDoesNotCompleteTheDefaultGesture() {
        showPad(EntryGesture.DOUBLE_TAP_HOLD)

        padTouch {
            twoFingerTap()
            advanceEventTime(150)
            twoFingerTap()
            advanceEventTime(150)
            twoFingerTap()
        }

        assertEquals(0, performed)
    }

    @Test
    fun aPinchToTwoXThenThreeTapsCompletesTheZoomGesture() {
        showPad(EntryGesture.PINCH_TRIPLE_TAP)

        padTouch { pinchToTwoX() }
        compose.onNodeWithText("2.0x").assertIsDisplayed()
        padTouch {
            tap()
            advanceEventTime(200)
            tap()
            advanceEventTime(200)
            tap()
        }

        assertEquals(1, performed)
    }

    @Test
    fun ordinaryTapsAtTheStartingZoomDoNotCompleteTheZoomGesture() {
        showPad(EntryGesture.PINCH_TRIPLE_TAP)

        padTouch {
            repeat(6) {
                tap()
                advanceEventTime(200)
            }
        }

        compose.onNodeWithText("1.0x").assertIsDisplayed()
        assertEquals(0, performed)
    }

    @Test
    fun aPinchToThreeXDoesNotArmTheZoomGesture() {
        showPad(EntryGesture.PINCH_TRIPLE_TAP)

        padTouch {
            down(0, Offset(center.x - 100f, center.y))
            down(1, Offset(center.x + 100f, center.y))
            moveTo(1, Offset(center.x + 500f, center.y))
            up(1)
            up(0)
            advanceEventTime(300)
            repeat(3) {
                tap()
                advanceEventTime(200)
            }
        }

        compose.onNodeWithText("3.0x").assertIsDisplayed()
        assertEquals(0, performed)
    }

    @Test
    fun aLongPressOnTheThumbnailThenASwipeLeftCompletesTheThumbnailGesture() {
        showPad(EntryGesture.THUMBNAIL_HOLD_SWIPE)

        compose.onNodeWithContentDescription("Open gallery").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(center.x - 200f, center.y))
            up()
        }

        assertEquals(1, performed)
    }

    @Test
    fun aTapOnTheThumbnailDoesNotCompleteTheThumbnailGesture() {
        showPad(EntryGesture.THUMBNAIL_HOLD_SWIPE)

        compose.onNodeWithContentDescription("Open gallery").performClick()

        assertEquals(0, performed)
    }

    @Test
    fun aSwipeStartedBeforeTheLongPressDoesNotCompleteTheThumbnailGesture() {
        showPad(EntryGesture.THUMBNAIL_HOLD_SWIPE)

        compose.onNodeWithContentDescription("Open gallery").performTouchInput {
            down(center)
            advanceEventTime(100)
            moveTo(Offset(center.x - 200f, center.y))
            advanceEventTime(700)
            up()
        }

        assertEquals(0, performed)
    }

    @Test
    fun threeCameraSwitchesThenAHeldShutterCompletesTheShutterGesture() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        repeat(3) { compose.onNodeWithContentDescription("Switch camera").performClick() }
        compose.onNodeWithContentDescription("Take photo").performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }

        assertEquals(1, performed)
    }

    @Test
    fun aQuickShutterTapAfterThreeSwitchesDoesNotCompleteTheShutterGesture() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        repeat(3) { compose.onNodeWithContentDescription("Switch camera").performClick() }
        compose.onNodeWithContentDescription("Take photo").performClick()

        assertEquals(0, performed)
    }

    @Test
    fun aHeldShutterWithoutTheSwitchesDoesNotCompleteTheShutterGesture() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        compose.onNodeWithContentDescription("Take photo").performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }

        assertEquals(0, performed)
    }

    // Regression: the Photo and Video switch sits outside every gesture region, so each gesture must still complete
    // once video mode is on, and the shutter gesture must not start a recording.

    @Test
    fun theDefaultGestureStillCompletesInVideoMode() {
        showPad(EntryGesture.DOUBLE_TAP_HOLD)
        enterVideoMode()

        padTouch { doubleTapThenHold() }

        assertEquals(1, performed)
    }

    @Test
    fun thePinchGestureStillCompletesInVideoMode() {
        showPad(EntryGesture.PINCH_TRIPLE_TAP)
        enterVideoMode()

        padTouch { pinchToTwoX() }
        padTouch {
            tap()
            advanceEventTime(200)
            tap()
            advanceEventTime(200)
            tap()
        }

        assertEquals(1, performed)
    }

    @Test
    fun theThumbnailGestureStillCompletesInVideoMode() {
        showPad(EntryGesture.THUMBNAIL_HOLD_SWIPE)
        enterVideoMode()

        compose.onNodeWithContentDescription("Open gallery").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(Offset(center.x - 200f, center.y))
            up()
        }

        assertEquals(1, performed)
    }

    @Test
    fun theShutterGestureStillCompletesInVideoModeAndStartsNoRecording() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)
        enterVideoMode()

        threeSwitchesThenHoldShutter("Start recording")

        assertEquals(1, performed)
    }

    @Test
    fun tappingPhotoAndVideoDoesNotCountAsACameraSwitch() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)

        repeat(3) {
            enterVideoMode()
            compose.onNodeWithText("PHOTO").performClick()
        }
        compose.onNodeWithContentDescription("Take photo").performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }

        assertEquals(0, performed)
    }

    @Test
    fun aTapOnTheRecordButtonInVideoModeReachesTheShutter() {
        showPad(EntryGesture.SWITCH_THEN_SHUTTER_HOLD)
        enterVideoMode()

        compose.onNodeWithContentDescription("Start recording").performClick()

        assertEquals(1, shutterClicks)
        assertEquals(0, performed)
    }
}
