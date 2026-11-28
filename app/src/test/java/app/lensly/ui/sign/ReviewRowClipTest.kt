package app.lensly.ui.sign

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * A sender can stack hundreds of combining marks on one letter of a text a review shows, such as the claimed origin. Each row of a review
 * must draw inside its own bounds, so such a text cannot cover the label or the start of the value of the row above or below it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewRowClipTest {
    @get:Rule
    val compose = createComposeRule()

    private val from = "0x1111111111111111111111111111111111111111"
    private val to = "0x2222222222222222222222222222222222222222"

    /** The pixels of the row tagged [tag], which holds whatever a neighbouring row drew over it. */
    private fun pixelsOf(tag: String): IntArray {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }

    private fun changedPixels(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        return a.indices.count { a[it] != b[it] }
    }

    private fun neighboursStayUnchangedBy(stacked: String) {
        var origin by mutableStateOf("a")
        compose.setContent {
            LumenTheme {
                Column {
                    ReviewField(label = "From", value = from, logo = {}, isAddress = true, modifier = Modifier.testTag("from"))
                    ReviewField(label = "Claimed Origin", value = origin, logo = {}, isAddress = false, modifier = Modifier.testTag("origin"))
                    ReviewField(label = "To", value = to, logo = {}, isAddress = true, modifier = Modifier.testTag("to"))
                }
            }
        }
        compose.waitForIdle()
        val fromBefore = pixelsOf("from")
        val toBefore = pixelsOf("to")

        origin = stacked
        compose.waitForIdle()

        assertEquals(0, changedPixels(fromBefore, pixelsOf("from")), "pixels of the From row changed")
        assertEquals(0, changedPixels(toBefore, pixelsOf("to")), "pixels of the To row changed")
    }

    @Test
    fun marksStackedBelowALetterDoNotDrawIntoTheRowUnderIt() = neighboursStayUnchangedBy(("o" + "̲".repeat(20)).repeat(12))

    @Test
    fun marksStackedAboveAndBelowALetterDoNotDrawIntoTheRowsAroundIt() =
        neighboursStayUnchangedBy(("o" + "̩̍".repeat(10)).repeat(12))
}
