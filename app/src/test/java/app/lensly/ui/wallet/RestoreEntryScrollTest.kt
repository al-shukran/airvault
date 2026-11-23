package app.lensly.ui.wallet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A 24-word restore on a phone-sized window. The slot of the word being typed must lie inside the word list at every
 * position, including past word 15, where the list first needs to scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port-mdpi")
class RestoreEntryScrollTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theWordBeingTypedIsInsideTheListAtEveryPositionOfTwentyFour() {
        var filled by mutableIntStateOf(0)
        compose.setContent {
            LumenTheme {
                RestoreEntryScreen(
                    state = RestoreState(words = List(filled) { "abandon" }, activeIndex = filled),
                    onKey = {},
                    onBackspace = {},
                    onPickSuggestion = {},
                    onEditWord = {},
                    onSubmit = {},
                )
            }
        }
        for (active in 0 until 24) {
            compose.runOnIdle { filled = active }
            compose.waitForIdle()
            assertNumberInList(active + 1)
        }
    }

    /** The slot numbered [number] is entirely within the scrolling word list. */
    private fun assertNumberInList(number: Int) {
        val list = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val slot = compose.onNodeWithText("%02d".format(number)).fetchSemanticsNode().boundsInRoot
        assertTrue(slot.top >= list.top && slot.bottom <= list.bottom, "word $number is outside the list: slot $slot, list $list")
    }
}
