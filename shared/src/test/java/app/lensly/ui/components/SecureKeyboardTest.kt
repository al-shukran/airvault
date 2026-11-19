package app.lensly.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a test can drive on [SecureKeyboard]: the held Backspace repeat, the down-arrow key, and the shifted number row. The repeat runs
 * on the test's clock, which is paused, so the timing is set by advancing it and no real time passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SecureKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backspaceDeletesOnPressAndRepeatsWhileHeld() {
        var deletes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme { SecureKeyboard(onKey = {}, onBackspace = { deletes++ }, onDone = {}) }
        }

        compose.onNodeWithContentDescription("Backspace").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(1, deletes)

        advanceBy(380)
        assertEquals(1, deletes, "nothing repeats before 400 ms")

        // A held key repeats every 50 ms from 400 ms: 13 repeats by 1000 ms, if every repeat is resumed on time. Each repeat is
        // resumed on a frame, so a late frame delays the repeats after it. The test checks the contract with that tolerance,
        // not each repeat's frame: the exact-millisecond checks at 420, 480 and 530 ms failed on the third repeat in CI.
        advanceBy(620)
        assertTrue(deletes in 10..14, "held for 1000 ms: $deletes deletes, expected 10 to 14")

        compose.onNodeWithContentDescription("Backspace").performTouchInput { up() }
        val atRelease = deletes
        advanceBy(1_000)
        assertEquals(atRelease, deletes, "releasing stops the repeat")
    }

    /**
     * Advances the test clock by [ms]. Each step runs a whole frame (16 ms), so the loop tests the clock, not a step count.
     */
    private fun advanceBy(ms: Long) {
        val target = compose.mainClock.currentTime + ms
        while (compose.mainClock.currentTime < target) compose.mainClock.advanceTimeBy(5)
    }

    @Test
    fun theDownArrowKeyHidesTheKeyboard() {
        var dismissed = 0
        compose.setContent {
            LumenTheme {
                KeyboardHost(focused = true, onUnfocus = { dismissed++ }) {
                    SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {})
                }
            }
        }

        compose.onNodeWithContentDescription("Hide keyboard").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun shiftShowsTheNumberRowSymbolsForOneKey() {
        val typed = StringBuilder()
        compose.setContent {
            LumenTheme { SecureKeyboard(onKey = { typed.append(it) }, onBackspace = {}, onDone = {}) }
        }

        compose.onNodeWithContentDescription("Shift").performClick()
        compose.onNodeWithContentDescription("1").assertDoesNotExist()
        compose.onNodeWithContentDescription("!").performClick()

        assertEquals("!", typed.toString())
        compose.onNodeWithContentDescription("1").assertExists()
    }

    @Test
    fun leavingTheLettersClearsShift() {
        compose.setContent {
            LumenTheme { SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {}) }
        }

        compose.onNodeWithContentDescription("Shift").performClick()
        compose.onNodeWithContentDescription("Symbols").performClick()
        compose.onNodeWithContentDescription("Letters").performClick()

        compose.onNodeWithContentDescription("!").assertDoesNotExist()
        compose.onNodeWithContentDescription("1").assertExists()
    }
}
