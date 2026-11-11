package app.lensly.ui.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Semantics of the controls a screen reader must reach, checked on the JVM in the PR lane (guideline 1.9).
 *
 * These are not a full accessibility audit: they assert what the quick gate can: each control reports a label to
 * accessibility services, it is a click action, and where the control is the touch target it is at least 48 dp.
 * Everything else, contrast and the reading order in particular, is the manual TalkBack pass in
 * docs/testing/release-checklist.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class SemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyPasswordFieldAnnouncesThatItIsEmpty() {
        compose.setContent { LenslyTheme { PasswordField(length = 0, modifier = Modifier.fillMaxWidth()) } }

        compose.onNodeWithContentDescription("Password empty").assertExists()
    }

    @Test
    fun filledPasswordFieldAnnouncesTheCharacterCount() {
        compose.setContent { LenslyTheme { PasswordField(length = 14, modifier = Modifier.fillMaxWidth()) } }

        compose.onNodeWithContentDescription("14 characters entered").assertExists()
    }

    @Test
    fun keypadKeysAreLabelledAndTallEnoughToTouch() {
        compose.setContent { LenslyTheme { NumericKeypad(onDigit = {}, onBackspace = {}) } }

        for (key in "0123456789".map { it.toString() } + "Backspace") {
            val node = compose.onNodeWithContentDescription(key).assertHasClickAction().fetchSemanticsNode()
            node.assertAtLeast48DpTall()
        }
    }

    @Test
    fun passwordKeyboardKeysAreLabelledAndTallEnoughToTouch() {
        compose.setContent {
            LenslyTheme {
                SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {})
            }
        }

        for (key in listOf("a", "q", "Shift", "Space", "Backspace", "Done", "Symbols")) {
            val node = compose.onNodeWithContentDescription(key).assertHasClickAction().fetchSemanticsNode()
            node.assertAtLeast48DpTall()
        }
    }

    @Test
    fun theScreenFrameBackButtonIsLabelledAnd48Dp() {
        compose.setContent {
            LenslyTheme {
                LenslyScreen(title = "Review", onBack = {}) {
                    Column {}
                }
            }
        }

        val node = compose.onNodeWithContentDescription("Back").assertHasClickAction().fetchSemanticsNode()
        node.assertAtLeast48DpTall()
        assertAtLeast48Dp(isWidth = true, node = node)
    }

    /**
     * A key is 54 dp tall and stretches with the row. Ten keys on a phone are narrower than 48 dp, which is a layout
     * choice this test records rather than fixes: the width cannot grow without dropping keys from the row.
     */
    private fun SemanticsNode.assertAtLeast48DpTall() = assertAtLeast48Dp(isWidth = false, node = this)

    /**
     * The touch bounds, not the layout bounds: a Material component may draw at 40 dp and extend its touch target to
     * 48 dp, and it is the target that a finger has to hit.
     */
    private fun assertAtLeast48Dp(isWidth: Boolean, node: SemanticsNode) {
        val minimum = with(compose.density) { 48.dp.toPx() }
        val target = node.touchBoundsInRoot
        val actual = if (isWidth) target.width else target.height
        assertTrue(
            "the target is ${actual / compose.density.density} dp on the " +
                (if (isWidth) "width" else "height") + ", under 48 dp",
            actual >= minimum - 0.5f,
        )
    }
}
