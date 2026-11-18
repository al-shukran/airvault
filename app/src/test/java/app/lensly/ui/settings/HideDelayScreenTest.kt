package app.lensly.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
 * The hide delay: the shortest and the longest delay in two number fields. A number is applied as it is typed while the range stays
 * valid, and a number that would break the range is refused and the range is named.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class HideDelayScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** The screen with its saved delays held as state, as the route holds them, and every applied value recorded. */
    private fun show(shortest: Int, longest: Int, applied: MutableList<String>) {
        compose.setContent {
            LumenTheme {
                var savedShortest by remember { mutableStateOf<Int?>(shortest) }
                var savedLongest by remember { mutableStateOf<Int?>(longest) }
                HideDelayScreen(
                    shortest = savedShortest,
                    longest = savedLongest,
                    onShortest = {
                        applied += "shortest=$it"
                        savedShortest = it
                    },
                    onLongest = {
                        applied += "longest=$it"
                        savedLongest = it
                    },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun theSavedDelaysAreShown() {
        show(shortest = 100, longest = 1_000, applied = mutableListOf())

        compose.onNodeWithText("100").assertExists()
        compose.onNodeWithText("1000").assertExists()
    }

    @Test
    fun aShortestDelayInItsRangeIsAppliedAsItIsTyped() {
        val applied = mutableListOf<String>()
        show(shortest = 100, longest = 1_000, applied = applied)

        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("250")

        assertEquals("shortest=250", applied.last())
    }

    @Test
    fun aShortestDelayAboveTheLongestIsRefusedAndTheRangeIsNamed() {
        val applied = mutableListOf<String>()
        show(shortest = 100, longest = 1_000, applied = applied)

        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("1500")

        // The text is typed in one step, so 1500 is the whole value: it is above the longest delay, so nothing is applied.
        assertTrue(applied.isEmpty(), "1500 is above the longest delay: $applied")
        compose.onNodeWithText("Enter a number from 0 to 1000.").assertExists()
    }

    @Test
    fun aLongestDelayBelowTheShortestIsRefusedAndTheRangeIsNamed() {
        val applied = mutableListOf<String>()
        show(shortest = 100, longest = 1_000, applied = applied)

        compose.onAllNodes(hasSetTextAction())[1].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("50")

        assertTrue(applied.none { it.startsWith("longest=") }, "50 is below the shortest delay: $applied")
        compose.onNodeWithText("Enter a number from 100 to 5000.").assertExists()
    }

    @Test
    fun aLongestDelayAboveTheLimitIsRefused() {
        val applied = mutableListOf<String>()
        show(shortest = 100, longest = 1_000, applied = applied)

        compose.onAllNodes(hasSetTextAction())[1].performTextClearance()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("6000")

        assertTrue(applied.none { it == "longest=6000" }, "6000 is above the limit: $applied")
    }
}
