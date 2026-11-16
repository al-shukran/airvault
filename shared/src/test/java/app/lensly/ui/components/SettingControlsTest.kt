package app.lensly.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The setting controls: what each one shows, and what a tap, a step or a typed number applies. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SettingControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRadioRowShowsItsTitleItsDescriptionAndItsState() {
        compose.setContent {
            LenslyTheme {
                RadioRow(title = "Strict", description = "Each amount is verified.", selected = true, onSelect = {})
            }
        }

        compose.onNodeWithText("Strict").assertIsDisplayed()
        compose.onNodeWithText("Each amount is verified.").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
    }

    @Test
    fun aTapOnARadioRowChoosesIt() {
        var chosen = 0
        compose.setContent {
            LenslyTheme {
                RadioRow(title = "Lumio only", description = "Only marked requests.", selected = false, onSelect = { chosen++ })
            }
        }

        compose.onNodeWithText("Lumio only").performClick()

        assertEquals(1, chosen)
    }

    @Test
    fun aSliderShowsTheChosenValueBigWithItsCaption() {
        compose.setContent {
            LenslyTheme {
                SliderSetting(
                    title = "Longest wait",
                    options = WAITS,
                    selected = "10 s",
                    label = { it },
                    onSelect = {},
                    caption = { if (it == "10 s") "Default" else null },
                )
            }
        }

        compose.onNodeWithText("Longest wait").assertIsDisplayed()
        compose.onNodeWithText("10 s").assertIsDisplayed()
        compose.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun aSliderStepChoosesTheValueItLandsOn() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            LenslyTheme {
                SliderSetting(
                    title = "Longest wait",
                    options = WAITS,
                    selected = "10 s",
                    label = { it },
                    onSelect = { chosen += it },
                    caption = { if (it == "10 s") "Default" else null },
                )
            }
        }

        // Index 3 of the eight waits is 30 s.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(3f) }

        assertEquals(listOf("30 s"), chosen)
        compose.onNodeWithText("30 s").assertIsDisplayed()
        compose.onNodeWithText("Default").assertDoesNotExist()
    }

    @Test
    fun aNumberInItsRangeIsAppliedAsItIsTyped() {
        val applied = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                NumberFieldSetting(
                    title = "Fee rate above",
                    value = 200,
                    range = 1..10_000,
                    suffix = "sat/vB",
                    onValid = { applied += it },
                )
            }
        }

        compose.onNodeWithText("sat/vB").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("250")

        assertEquals(250, applied.last())
        compose.onNodeWithText("Enter a number from 1 to 10000.").assertDoesNotExist()
    }

    @Test
    fun aNumberOutsideItsRangeIsNotAppliedAndTheRangeIsNamed() {
        val applied = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                NumberFieldSetting(
                    title = "Fee rate above",
                    value = 200,
                    range = 1..10_000,
                    suffix = "sat/vB",
                    onValid = { applied += it },
                )
            }
        }

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("0")

        assertTrue(applied.isEmpty(), "A number outside the range must not be applied: $applied")
        compose.onNodeWithText("Enter a number from 1 to 10000.").assertIsDisplayed()
    }

    @Test
    fun aChoiceRowShowsItsLabelAndValue() {
        compose.setContent {
            LenslyTheme {
                ChoiceDialogRow(
                    label = "Bulk cap",
                    value = "100",
                    options = listOf(20, 50, 100),
                    selected = 100,
                    optionLabel = { it.toString() },
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("Bulk cap · 100").assertIsDisplayed()
    }

    @Test
    fun aChoiceRowOpensARadioDialogAndAChoiceIsAppliedAndClosesIt() {
        val chosen = mutableListOf<Int>()
        compose.setContent {
            LenslyTheme {
                ChoiceDialogRow(
                    label = "Bulk cap",
                    value = "100",
                    options = listOf(20, 50, 100),
                    selected = 100,
                    optionLabel = { it.toString() },
                    onSelect = { chosen += it },
                )
            }
        }

        compose.onNodeWithText("Bulk cap · 100").performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("50").performClick()

        assertEquals(listOf(50), chosen)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    private companion object {
        val WAITS = listOf("1 s", "5 s", "10 s", "30 s", "1 min", "5 min", "15 min", "1 h")
    }
}
