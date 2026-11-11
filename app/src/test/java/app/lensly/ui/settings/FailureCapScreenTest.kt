package app.lensly.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wrong password lockout: its wording, the waits each choice sets, and the warning under 10 s. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class FailureCapScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(saved: FailureCapChoice?, selected: FailureCapChoice?) {
        compose.setContent {
            LenslyTheme {
                FailureCapScreen(
                    step = FailureCapStep.Choose,
                    saved = saved,
                    selected = selected,
                    passwordLength = 0,
                    onSelect = {},
                    onContinue = {},
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun theScreenIsTitledWrongPasswordLockout() {
        show(saved = FailureCapChoice.TEN_SECONDS, selected = null)

        compose.onNodeWithText("WRONG PASSWORD LOCKOUT").assertIsDisplayed()
    }

    @Test
    fun oneMinuteShowsTheWaitsTenTwentyFortySixtyAndSixty() {
        show(saved = FailureCapChoice.TEN_SECONDS, selected = FailureCapChoice.ONE_MINUTE)

        compose.onNodeWithContentDescription("Waits after wrong tries: 10 s, 20 s, 40 s, 1 min, 1 min").assertExists()
    }

    @Test
    fun theWaitsFollowTheChoiceAsItChanges() {
        compose.setContent {
            LenslyTheme {
                var selected by remember { mutableStateOf<FailureCapChoice?>(null) }
                FailureCapScreen(
                    step = FailureCapStep.Choose,
                    saved = FailureCapChoice.DEFAULT,
                    selected = selected,
                    passwordLength = 0,
                    onSelect = { selected = it },
                    onContinue = {},
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }
        // The pills are uppercase on screen.
        compose.onNodeWithText("5 S").performClick()

        compose.onNodeWithContentDescription("Waits after wrong tries: 5 s, 5 s, 5 s, 5 s, 5 s").assertExists()
    }

    @Test
    fun aCapBelowTenSecondsWarnsAndAsksToContinueAnyway() {
        show(saved = FailureCapChoice.DEFAULT, selected = FailureCapChoice.ONE_SECOND)

        compose.onNodeWithText("Short waits make guessing your password faster", substring = true).assertExists()
        compose.onNodeWithText("CONTINUE ANYWAY").assertExists()
    }

    @Test
    fun theExplanationNamesTheSevenDayLimit() {
        show(saved = FailureCapChoice.DEFAULT, selected = null)

        compose.onNodeWithText("During the first 7 days after setup, no wait is longer than 10 s.").assertExists()
    }

    @Test
    fun theWaitStepsAreTheDomainsWaits() {
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L), waitSteps(FailureCapChoice.ONE_MINUTE))
        assertEquals(waitSteps(FailureCapChoice.ONE_MINUTE), waitSteps(FailureCapChoice.DEFAULT))
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_000L, 1_000L), waitSteps(FailureCapChoice.ONE_SECOND))
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L), waitSteps(FailureCapChoice.ONE_HOUR))
    }

    @Test
    fun waitsAreWrittenTheWayTheChoicesNameThem() {
        assertEquals("10 s", formatWait(10_000L))
        assertEquals("80 s", formatWait(80_000L))
        assertEquals("1 min", formatWait(60_000L))
        assertEquals("15 min", formatWait(900_000L))
        assertEquals("1 h", formatWait(3_600_000L))
    }

    @Test
    fun theSettingsRowNamesTheLongestWait() {
        assertEquals("Up to 1 min", FailureCapChoice.ONE_MINUTE.summary)
        assertEquals("Up to 1 min (default)", FailureCapChoice.DEFAULT.summary)
        assertEquals("Up to 1 h", FailureCapChoice.ONE_HOUR.summary)
    }
}
