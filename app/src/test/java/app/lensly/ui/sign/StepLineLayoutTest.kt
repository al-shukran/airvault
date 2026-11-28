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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.SectionStyle
import app.airvault.core.model.text
import app.lensly.ui.components.groupAddress
import app.lensly.ui.screenshot.EvmReviewScreen
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.aaveSupplySample
import app.lensly.ui.screenshot.urBatchPayingOtherSample
import app.lensly.ui.screenshot.urBatchRealSample
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The step lines of an EVM review, and the warnings its steps raise, name accounts in groups of four, such as `0x1593 D398 … 3eA2 5343`.
 * A step sentence may take more than one line, but it is never clipped or cut with an ellipsis, a line never runs past the width of the
 * screen, and an address is never split across two lines. The last is shown by joining the groups of an address with no-break spaces.
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
    fun aUniversalRouterBatchPayingTheSignerFitsTheScreen() = assertStepTextFits(urBatchRealSample())

    @Test
    fun anAaveSupplyForAnotherAccountFitsTheScreen() = assertStepTextFits(aaveSupplySample())

    @Test
    fun anAddressInAUniversalRouterStepLineIsNeverSplitAcrossLines() = assertAddressesStayWhole(urBatchPayingOtherSample())

    @Test
    fun anAddressInAnAaveStepLineIsNeverSplitAcrossLines() = assertAddressesStayWhole(aaveSupplySample())

    @Test
    fun onlyTheGroupsOfAnAddressAreJoined() {
        val nb = NO_BREAK_SPACE
        assertEquals("Pay 0x1593${nb}D398${nb}…${nb}3eA2${nb}5343 now", keepAddressesWhole("Pay 0x1593 D398 … 3eA2 5343 now"))
        val whole = "0x1111 2222 3333 4444 5555 6666 7777 8888 9999 aaaa"
        assertEquals(whole.replace(' ', nb) + " beef", keepAddressesWhole("$whole beef"))
        assertEquals("Unknown bulker action 0x1234 56789: raw", keepAddressesWhole("Unknown bulker action 0x1234 56789: raw"))
    }

    private fun assertAddressesStayWhole(review: Review) {
        show(review)
        recipientTexts(review).forEach { text ->
            // A Recipient row wraps between groups, as every address row does, so each line holds whole groups of four.
            layoutOf(text).let { layout ->
                val shown = layout.layoutInput.text.text
                (0 until layout.lineCount).forEach { line ->
                    shown.substring(layout.getLineStart(line), layout.getLineEnd(line)).trim().split(' ').forEach { group ->
                        assertTrue(GROUP.matches(group), "line ${line + 1} of the Recipient $text breaks inside the group \"$group\"")
                    }
                }
            }
        }
        var addresses = 0
        stepTexts(review).forEach { text ->
            val layout = layoutOf(text)
            val shown = layout.layoutInput.text.text
            ADDRESS.findAll(shown).forEach { address ->
                addresses++
                assertEquals(
                    layout.getLineForOffset(address.range.first),
                    layout.getLineForOffset(address.range.last),
                    "the address ${address.value} in \"$text\" is split across lines",
                )
            }
        }
        assertTrue(addresses > 0, "the steps of the review name an account in groups of four")
    }

    /** The grouped text of each Recipient row, which the review adds for an account other than the signer that a Universal Router batch pays. It is an address row, not plain text. */
    private fun recipientTexts(review: Review): List<String> {
        val rows = review.sections.flatMap { it.rows }.filter { it.label == "Recipient" }
        return rows.map { row -> groupAddress(assertIs<DisplayValue.Address>(row.value).text()) }
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
        val texts = stepTexts(review) + recipientTexts(review)
        assertTrue(texts.isNotEmpty(), "the review has step lines")
        show(review)
        texts.forEach { text ->
            val layout = layoutOf(text)
            assertFalse(layout.didOverflowHeight, "\"$text\" is clipped")
            val shown = layout.layoutInput.text.text
            assertEquals(shown, (0 until layout.lineCount).joinToString("") { shown.substring(layout.getLineStart(it), layout.getLineEnd(it)) }.trimEnd(), "\"$text\" shows whole")
            (0 until layout.lineCount).forEach { line ->
                assertFalse(layout.isLineEllipsized(line), "line ${line + 1} of \"$text\" ends in an ellipsis")
                assertTrue(layout.getLineRight(line) <= layout.layoutInput.constraints.maxWidth, "line ${line + 1} of \"$text\" runs past the screen")
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodes(SemanticsMatcher("shows \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(NO_BREAK_SPACE, ' ') == text } == true
        }).onFirst().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    private companion object {
        const val DENSITY = 1.75f

        /** An account in groups of four, as a step line shows it. */
        val ADDRESS = Regex("0x[0-9A-Fa-f]{4}([ \u00A0][0-9A-Fa-f]{4}|[ \u00A0]…)+")
        val GROUP = Regex("(0x)?[0-9A-Fa-f]{4}")
        const val NO_BREAK_SPACE = '\u00A0'
    }
}
