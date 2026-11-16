package app.lensly.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fee warnings: the rate typed into its field and the share picked on its slider, each applied as it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class FeeWarningScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSavedLimitsAreShown() {
        compose.setContent {
            LenslyTheme {
                FeeWarningScreen(rate = 200, share = 10, onRate = {}, onShare = {}, onBack = {})
            }
        }

        compose.onNodeWithText("sat/vB").assertExists()
        compose.onNodeWithText("10%").assertExists()
    }

    @Test
    fun aRateInItsRangeIsAppliedAsItIsTyped() {
        val applied = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                FeeWarningScreen(rate = 200, share = 10, onRate = { applied += it }, onShare = {}, onBack = {})
            }
        }

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("250")

        assertEquals(250, applied.last())
    }

    @Test
    fun aRateOutsideItsRangeIsNotAppliedAndTheRangeIsNamed() {
        val applied = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                FeeWarningScreen(rate = 200, share = 10, onRate = { applied += it }, onShare = {}, onBack = {})
            }
        }

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("0")

        assertTrue(applied.isEmpty(), "A rate outside 1 to 10000 must not be applied: $applied")
        compose.onNodeWithText("Enter a number from 1 to 10000.").assertExists()
    }

    @Test
    fun theShareSliderAppliesTheStopItEndsOn() {
        val applied = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                FeeWarningScreen(rate = 200, share = 10, onRate = {}, onShare = { applied += it }, onBack = {})
            }
        }

        // Index 49 of the one-to-one-hundred shares is 50 per cent.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(49f) }

        assertEquals(listOf(50), applied)
    }
}
