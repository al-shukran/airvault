package app.lensly.ui.gate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.components.waitUntilKeyboardHidden
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The unlock keyboard. It is up when the screen opens, a tap outside the password field hides it, and a tap on the field brings it
 * back. Typing reaches the screen both before and after it hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class UnlockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val typed = StringBuilder()

    @Test
    fun theKeyboardIsUpWhenTheScreenOpens() {
        setUnlock()

        compose.onNodeWithContentDescription("Done").assertExists()
    }

    @Test
    fun aTapOutsideHidesTheKeyboardAndATapOnTheFieldBringsItBack() {
        setUnlock()

        compose.onNodeWithText("APP PASSWORD").performClick()
        compose.waitUntilKeyboardHidden()

        compose.onNodeWithContentDescription("Password empty").performClick()
        compose.onNodeWithContentDescription("Done").assertExists()
    }

    @Test
    fun typingStillReachesTheScreenBeforeAndAfterTheKeyboardHides() {
        setUnlock()
        compose.onNodeWithContentDescription("a").performClick()

        compose.onNodeWithText("APP PASSWORD").performClick()
        compose.waitUntilKeyboardHidden()

        compose.onNodeWithContentDescription("1 characters entered").performClick()
        compose.onNodeWithContentDescription("b").performClick()

        assertEquals("ab", typed.toString())
        compose.onNodeWithContentDescription("2 characters entered").assertExists()
    }

    /** Hosts the unlock screen with the typed length in state, so the password field counts what the keys add. */
    private fun setUnlock() {
        compose.setContent {
            var length by remember { mutableIntStateOf(0) }
            LenslyTheme {
                UnlockScreen(
                    length = length,
                    busy = false,
                    onKey = { key ->
                        typed.append(key)
                        length = typed.length
                    },
                    onBackspace = {},
                    onSubmit = {},
                )
            }
        }
    }
}
