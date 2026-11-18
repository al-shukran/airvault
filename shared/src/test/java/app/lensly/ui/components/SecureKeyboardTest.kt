package app.lensly.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
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

        compose.mainClock.advanceTimeBy(300)
        assertEquals(1, deletes, "nothing repeats before 400 ms")
        compose.mainClock.advanceTimeBy(120)
        assertEquals(2, deletes, "the first repeat is due at 400 ms")
        compose.mainClock.advanceTimeBy(60)
        assertEquals(3, deletes, "the second repeat is due at 450 ms")
        compose.mainClock.advanceTimeBy(50)
        assertEquals(4, deletes, "the third repeat is due at 500 ms")

        compose.onNodeWithContentDescription("Backspace").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(4, deletes, "releasing stops the repeat")
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
