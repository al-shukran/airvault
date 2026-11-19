package app.lensly.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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

    private fun advanceTo(ms: Long) {
        while (compose.mainClock.currentTime < ms) compose.mainClock.advanceTimeBy(5)
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

    /** The centre of the node that [description] names, in window pixels. */
    private fun centreOf(description: String): Offset =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot.center

    /** The centre of cell [index] of the open strip of [count] alternates, in window pixels. */
    private fun alternateCentre(index: Int, count: Int): Offset {
        val strip = compose.onNodeWithTag(KEY_ALTERNATES_TAG).fetchSemanticsNode().boundsInRoot
        val cell = strip.width / count
        return Offset(strip.left + (index + 0.5f) * cell, strip.center.y)
    }

    /** Runs one frame, so a state change recomposes, then lets the pending work finish. The test clock is paused, so no frame runs by itself. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun keyboardWithTyped(typed: StringBuilder) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme { SecureKeyboard(onKey = { typed.append(it) }, onBackspace = {}, onDone = {}) }
        }
    }

    @Test
    fun aTapTypesTheLetter() {
        val typed = StringBuilder()
        keyboardWithTyped(typed)

        compose.onRoot().performTouchInput { down(centreOf("e")) }
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals("e", typed.toString())
    }

    @Test
    fun aHeldLetterOpensItsAlternatesAndReleaseOnOneTypesIt() {
        val typed = StringBuilder()
        keyboardWithTyped(typed)

        compose.onRoot().performTouchInput { down(centreOf("e")) }
        advanceTo(ALTERNATES_LONG_PRESS_MS + 20)
        compose.onNodeWithTag(KEY_ALTERNATES_TAG).assertExists()
        compose.onRoot().performTouchInput { moveTo(alternateCentre(1, 7)) }
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals("é", typed.toString())
        compose.onNodeWithTag(KEY_ALTERNATES_TAG).assertDoesNotExist()
    }

    @Test
    fun releasingAHeldLetterOnTheKeyItselfTypesNothing() {
        val typed = StringBuilder()
        keyboardWithTyped(typed)

        compose.onRoot().performTouchInput { down(centreOf("e")) }
        advanceTo(ALTERNATES_LONG_PRESS_MS + 20)
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals("", typed.toString())
        compose.onNodeWithTag(KEY_ALTERNATES_TAG).assertDoesNotExist()
    }

    @Test
    fun aFingerThatLeavesTheKeyBeforeTheLongPressCancelsTheTap() {
        val typed = StringBuilder()
        keyboardWithTyped(typed)

        compose.onRoot().performTouchInput { down(centreOf("e")) }
        compose.onRoot().performTouchInput { moveTo(centreOf("r")) }
        advanceTo(ALTERNATES_LONG_PRESS_MS + 20)
        compose.onNodeWithTag(KEY_ALTERNATES_TAG).assertDoesNotExist()
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals("", typed.toString())
    }

    @Test
    fun aHeldLetterWithShiftOnTypesTheUppercaseAlternate() {
        val typed = StringBuilder()
        keyboardWithTyped(typed)
        compose.onNodeWithContentDescription("Shift").performClick()

        settle()

        compose.onRoot().performTouchInput { down(centreOf("E")) }
        advanceTo(ALTERNATES_LONG_PRESS_MS + 20)
        compose.onRoot().performTouchInput { moveTo(alternateCentre(0, 7)) }
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals("È", typed.toString())
        compose.onNodeWithContentDescription("e").assertExists()
    }

    @Test
    fun aPressedLetterShowsItsPreviewUntilReleased() {
        keyboardWithTyped(StringBuilder())

        compose.onNodeWithContentDescription("a").performTouchInput { down(center) }
        settle()
        compose.onNodeWithTag(KEY_PREVIEW_TAG).assertExists()
        compose.onNodeWithContentDescription("a").performTouchInput { up() }
        settle()

        compose.onNodeWithTag(KEY_PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun spaceBackspaceAndShiftShowNoPreview() {
        keyboardWithTyped(StringBuilder())

        for (description in listOf("Space", "Backspace", "Shift")) {
            compose.onNodeWithContentDescription(description).performTouchInput { down(center) }
            settle()
            compose.onNodeWithTag(KEY_PREVIEW_TAG).assertDoesNotExist()
            compose.onNodeWithContentDescription(description).performTouchInput { up() }
            settle()
        }
    }

    @Test
    fun thePreviewGivesWayToTheAlternatesWhileTheyAreOpen() {
        keyboardWithTyped(StringBuilder())

        compose.onRoot().performTouchInput { down(centreOf("e")) }
        settle()
        compose.onNodeWithTag(KEY_PREVIEW_TAG).assertExists()
        advanceTo(ALTERNATES_LONG_PRESS_MS + 20)

        compose.onNodeWithTag(KEY_PREVIEW_TAG).assertDoesNotExist()
        compose.onNodeWithTag(KEY_ALTERNATES_TAG).assertExists()
        compose.onRoot().performTouchInput { up() }
        settle()
    }
}
