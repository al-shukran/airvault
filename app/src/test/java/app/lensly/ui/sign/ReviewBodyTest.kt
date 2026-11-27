package app.lensly.ui.sign

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The review's list draws its scroll fade only over a list that scrolls, and the line above Hold to Sign shows whole at large fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class ReviewBodyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var read: ReviewRead

    /** Shows a list of [contentDp] in a viewport of 400 dp. */
    private fun showList(contentDp: Int) {
        compose.setContent {
            LumenTheme {
                Column(Modifier.height(400.dp)) {
                    read = rememberReviewRead(Unit)
                    ReviewBody(read) { Box(Modifier.height(contentDp.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aListThatFitsByATinyMarginDrawsNoFadeAndCountsAsRead() {
        showList(contentDp = 390)
        compose.onNodeWithTag(SCROLL_FADE_TAG).assertDoesNotExist()
        assertTrue(read.read)
    }

    @Test
    fun aListThatScrollsDrawsTheFadeAndIsUnread() {
        showList(contentDp = 900)
        compose.onNodeWithTag(SCROLL_FADE_TAG).assertIsDisplayed()
        assertFalse(read.read)
    }

    @Test
    fun theReadHintShowsWholeAtDoubleFontScaleOnANarrowScreen() = assertHintShowsWhole("Scroll to the end to review everything")

    @Test
    fun theTickHintShowsWholeAtDoubleFontScaleOnANarrowScreen() = assertHintShowsWhole("Tick the checkboxes above to sign")

    private fun assertHintShowsWhole(hint: String) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                LumenTheme {
                    Box(Modifier.width(360.dp)) {
                        ReviewActions(hint = hint, approvable = false, onApprove = {}, onReject = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        assertFalse(textLayout(compose.onNodeWithText(hint)).hasVisualOverflow, "the hint is cut off")
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }
}
