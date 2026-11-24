package app.lumio.ui.kit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TalkBack adjusts a stop slider one stop at a time and reads the stop's name, so the Auto-Lock and fee choices work without touch (#832). */
@RunWith(RobolectricTestRunner::class)
class ClearAirStopSliderTest {
    @get:Rule
    val compose = createComposeRule()

    private val stops = listOf("Slow", "Normal", "Fast")

    @Test
    fun theSliderReportsItsRangeAndTheNameOfTheSelectedStop() {
        compose.setContent { ClearAirTheme { ClearAirStopSlider(stops, selected = 1, onSelect = {}) } }

        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, range.current)
        assertEquals(0f..2f, range.range)
        assertEquals(1, range.steps)
        assertEquals("Normal", node.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun aTalkBackAdjustmentMovesTheSliderToTheStopItAsksFor() {
        val chosen = mutableListOf<Int>()
        compose.setContent { ClearAirTheme { ClearAirStopSlider(stops, selected = 0, onSelect = { chosen += it }) } }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(1.6f) }

        assertEquals(listOf(2), chosen)
    }
}
