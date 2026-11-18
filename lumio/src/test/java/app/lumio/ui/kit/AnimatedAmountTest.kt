package app.lumio.ui.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.lumio.ui.theme.LocalReducedMotion
import app.lumio.ui.theme.LumioMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The amount's motion (section 3.4): reduced motion and a hidden balance show the final string at once, and a count-up runs once. */
@RunWith(RobolectricTestRunner::class)
class AnimatedAmountTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reducedMotionShowsTheFinalStringAtOnce() {
        compose.mainClock.autoAdvance = false
        showAmount(text = "\$12.00", reduced = true, countUp = { "\$%.2f".format(12.0 * it) })

        compose.onNodeWithText("\$12.00").assertExists()
        compose.onNodeWithText("\$0.00").assertDoesNotExist()
    }

    @Test
    fun hiddenBalanceShowsTheMaskAndNeverChanges() {
        compose.mainClock.autoAdvance = false
        var amount by mutableStateOf("\$12.00")
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                MaterialTheme { AnimatedAmount(text = amount, hidden = true) }
            }
        }

        compose.onNodeWithText(HIDDEN_AMOUNT).assertExists()
        compose.runOnIdle { amount = "\$13.00" }
        compose.onNodeWithText(HIDDEN_AMOUNT).assertExists()
        compose.onNodeWithText("\$13.00").assertDoesNotExist()
        compose.onNodeWithContentDescription("\$13.00").assertDoesNotExist()
    }

    @Test
    fun countUpRunsFromZeroThenShowsTheExactString() {
        compose.mainClock.autoAdvance = false
        showAmount(text = "\$12.00", reduced = false, countUp = { "\$%.2f".format(12.0 * it) })

        compose.onNodeWithText("\$0.00").assertExists()
        compose.mainClock.advanceTimeBy(LumioMotion.COUNT_UP / 2L)
        compose.onNodeWithText("\$12.00").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(LumioMotion.COUNT_UP.toLong())
        compose.onNodeWithContentDescription("\$12.00").assertExists()
    }

    @Test
    fun cachedValueShownOnOpenNeverCountsUp() {
        compose.mainClock.autoAdvance = false
        showAmount(text = "\$12.00", reduced = false, countUp = null)

        compose.onNodeWithContentDescription("\$12.00").assertExists()
        compose.mainClock.advanceTimeBy(LumioMotion.COUNT_UP.toLong())
        compose.onNodeWithContentDescription("\$12.00").assertExists()
    }

    private fun showAmount(text: String, reduced: Boolean, countUp: ((Float) -> String)?) {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                MaterialTheme { AnimatedAmount(text = text, countUp = countUp) }
            }
        }
    }
}
