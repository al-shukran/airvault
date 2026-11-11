package app.lensly.ui.sign

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
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
    val compose = createComposeRule()

    private var signed = 0

    private fun showControl(enabled: Boolean) {
        signed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
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
    fun aDisabledControlTakesNoHoldAndSignsNothing() {
        showControl(enabled = false)
        compose.onNodeWithTag(HOLD_TO_SIGN_TAG).assertIsNotEnabled()

        press()
        compose.mainClock.advanceTimeBy(HOLD_TO_SIGN_MILLIS + 100L)
        release()

        assertEquals(0, signed)
    }
}
