package app.lensly.ui.settings

import app.lensly.core.vault.FailureDelay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wrong password lockout: its wording, the slider of caps, the waits each cap sets, and the warning under 10 s. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class FailureCapScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** The slider's one node: it is the only node that takes a progress action. */
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private fun show(saved: FailureCapChoice?, selected: FailureCapChoice?, onSelect: (FailureCapChoice) -> Unit = {}) {
        compose.setContent {
            LenslyTheme {
                FailureCapScreen(
                    step = FailureCapStep.Choose,
                    saved = saved,
                    selected = selected,
                    savedErase = FailureDelay.DEFAULT_WIPE_AFTER,
                    selectedErase = null,
                    passwordLength = 0,
                    onSelect = onSelect,
                    onSelectErase = {},
                    onContinue = {},
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
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
    fun theDefaultCaptionSitsUnderOneMinute() {
        show(saved = FailureCapChoice.TEN_SECONDS, selected = FailureCapChoice.ONE_MINUTE)

        compose.onAllNodesWithText("Default")[0].assertIsDisplayed()
        compose.onNodeWithText("1 min").assertIsDisplayed()
    }

    @Test
    fun theDefaultCaptionFollowsTheSliderNotTheSavedCap() {
        show(saved = FailureCapChoice.TEN_SECONDS, selected = FailureCapChoice.FIVE_MINUTES)

        compose.onAllNodesWithText("Default").assertCountEquals(1)
        compose.onNodeWithText("5 min").assertIsDisplayed()
    }

    @Test
    fun theWaitsFollowTheSliderAsItMoves() {
        compose.setContent {
            LenslyTheme {
                var selected by remember { mutableStateOf<FailureCapChoice?>(null) }
                FailureCapScreen(
                    step = FailureCapStep.Choose,
                    saved = FailureCapChoice.DEFAULT,
                    selected = selected,
                    savedErase = FailureDelay.DEFAULT_WIPE_AFTER,
                    selectedErase = null,
                    passwordLength = 0,
                    onSelect = { selected = it },
                    onSelectErase = {},
                    onContinue = {},
                    onKey = {},
                    onBackspace = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
        // Index 1 of the six caps is 30 s.
        compose.onAllNodes(slider)[0].performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }

        compose.onNodeWithContentDescription("Waits after wrong tries: 10 s, 20 s, 30 s, 30 s, 30 s").assertExists()
    }

    @Test
    fun aSliderStopIsChosenWhenTheSliderEnds() {
        val chosen = mutableListOf<FailureCapChoice>()
        show(saved = FailureCapChoice.DEFAULT, selected = FailureCapChoice.DEFAULT, onSelect = { chosen += it })

        // Index 4 of the six caps is 15 min.
        compose.onAllNodes(slider)[0].performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }

        assertEquals(listOf(FailureCapChoice.FIFTEEN_MINUTES), chosen)
    }

    @Test
    fun continueIsOffUntilThePickDiffersFromTheSavedCap() {
        show(saved = FailureCapChoice.DEFAULT, selected = FailureCapChoice.DEFAULT)

        compose.onNodeWithText("CONTINUE").assertIsNotEnabled()
    }

    @Test
    fun continueIsOnOnceAnotherCapIsPicked() {
        show(saved = FailureCapChoice.DEFAULT, selected = FailureCapChoice.ONE_HOUR)

        compose.onNodeWithText("CONTINUE").assertIsEnabled()
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
        assertEquals(listOf(10_000L, 10_000L, 10_000L, 10_000L, 10_000L), waitSteps(FailureCapChoice.TEN_SECONDS))
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
        assertEquals("Up to 1 min (default)", FailureCapChoice.ONE_MINUTE.summary)
        assertEquals("Up to 5 min", FailureCapChoice.FIVE_MINUTES.summary)
        assertEquals("Up to 1 h", FailureCapChoice.ONE_HOUR.summary)
    }

    @Test
    fun aSavedCapMapsToItsChoiceAndAMissingCapIsTheDefault() {
        assertEquals(FailureCapChoice.FIFTEEN_MINUTES, FailureCapChoice.of(900_000L))
        assertEquals(FailureCapChoice.ONE_MINUTE, FailureCapChoice.of(null))
        // A cap the app does not offer reads as the default, as it did before.
        assertEquals(FailureCapChoice.ONE_MINUTE, FailureCapChoice.of(45_000L))
    }

    @Test
    fun everyChoiceIsOneOfTheSixCapsFromTenSecondsToOneHour() {
        assertEquals(
            listOf("10 s", "30 s", "1 min", "5 min", "15 min", "1 h"),
            FailureCapChoice.entries.map { it.label },
        )
    }
}
