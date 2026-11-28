package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.airvault.core.model.Review
import app.airvault.core.model.SectionStyle
import app.lensly.ui.screenshot.EvmReviewScreen
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.aaveSupplySample
import app.lensly.ui.screenshot.urBatchPayingOtherSample
import app.lensly.ui.screenshot.urBatchSample
import app.lensly.ui.theme.LumenTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The step lines of an EVM review, and the warnings its steps raise, name accounts in groups of four, such as `0x1593 D398 … 3eA2 5343`.
 * A step sentence may take more than one line, but it is never clipped or cut with an ellipsis, a line never runs past the width of the
 * screen, and an address is never split across two lines. The last is an ignored test until the wrap is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepLineLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aUniversalRouterBatchPayingAnotherAccountFitsTheScreen() = assertStepTextFits(urBatchPayingOtherSample())

    @Test
    fun aUniversalRouterBatchPayingTheSignerFitsTheScreen() = assertStepTextFits(urBatchSample())

    @Test
    fun anAaveSupplyForAnotherAccountFitsTheScreen() = assertStepTextFits(aaveSupplySample())

    /**
     * Fails today: a step line that names another account wraps inside the address, so its groups are split over two lines. Fixing it
     * is a layout or copy change for the designer to approve, and this test is the check that change must pass. Remove @Ignore with it.
     */
    @Ignore("A step line wraps inside an address (issue 1694); the fix needs a design decision")
    @Test
    fun anAddressInAStepLineIsNeverSplitAcrossLines() {
        listOf(urBatchPayingOtherSample(), aaveSupplySample()).forEach { review ->
            show(review)
            stepTexts(review).forEach { text ->
                val layout = layoutOf(text)
                ADDRESS.findAll(text).forEach { address ->
                    assertEquals(
                        layout.getLineForOffset(address.range.first),
                        layout.getLineForOffset(address.range.last),
                        "the address ${address.value} in \"$text\" is split across lines",
                    )
                }
            }
        }
    }

    private fun stepTexts(review: Review): List<String> =
        review.sections.filter { it.style == SectionStyle.Steps }.flatMap { section -> section.rows.map { it.label } } +
            review.warnings.filter { it.code == "evm.steps" }.map { it.message }

    private fun show(review: Review) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                LumenTheme {
                    Box(Modifier.requiredSize((720f / DENSITY).dp, (1600f / DENSITY).dp).background(MaterialTheme.colorScheme.background)) {
                        EvmReviewScreen(review = review)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertStepTextFits(review: Review) {
        val texts = stepTexts(review)
        assertTrue(texts.isNotEmpty(), "the review has step lines")
        show(review)
        texts.forEach { text ->
            val layout = layoutOf(text)
            assertFalse(layout.didOverflowHeight, "\"$text\" is clipped")
            assertEquals(text, (0 until layout.lineCount).joinToString("") { text.substring(layout.getLineStart(it), layout.getLineEnd(it)) }.trimEnd(), "\"$text\" shows whole")
            (0 until layout.lineCount).forEach { line ->
                assertFalse(layout.isLineEllipsized(line), "line ${line + 1} of \"$text\" ends in an ellipsis")
                assertTrue(layout.getLineRight(line) <= layout.layoutInput.constraints.maxWidth, "line ${line + 1} of \"$text\" runs past the screen")
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private companion object {
        const val DENSITY = 1.75f

        /** An account in groups of four, as a step line shows it. */
        val ADDRESS = Regex("0x[0-9A-Fa-f]{4}( [0-9A-Fa-f]{4}| …)+")
    }
}
