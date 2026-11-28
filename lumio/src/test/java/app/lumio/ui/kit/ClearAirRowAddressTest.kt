package app.lumio.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Where a row puts its address (#1692): under the header band, 2 dp down, from the text's left edge, 8 dp above the bottom, with the band where a
 * row of the minimum height puts it; and in the description's place, 2 dp under the title, when the row has no description.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearAirRowAddressTest {
    @get:Rule
    val compose = createComposeRule()

    private val address = "0xf278cf59f5d0e2a6d0c3c2f4a9d2a3d9f25c1cdb"
    private val tolerance = 0.6.dp

    private fun near(expected: Float, actual: Float, what: String) = assertTrue(kotlin.math.abs(expected - actual) <= tolerance.value, "$what: expected $expected, was $actual")

    @Test
    fun theAddressSitsUnderTheBandFromTheTextsEdgeWithTheBandWhereTodaysRowPutsIt() {
        compose.setContent {
            ClearAirTheme {
                ClearAirRow(
                    title = "PulsarNova",
                    description = "2 assets",
                    address = address,
                    leading = { Box(Modifier.size(36.dp)) },
                    trailing = { Box(Modifier.size(20.dp)) },
                    chevron = false,
                )
            }
        }

        val line = compose.onNodeWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).getBoundsInRoot()
        val description = compose.onNodeWithText("2 assets", useUnmergedTree = true).getBoundsInRoot()
        val title = compose.onNodeWithText("PulsarNova", useUnmergedTree = true).getBoundsInRoot()
        near(68f, line.left.value, "the address starts where the text does (20 + 36 + 12)")
        // The 36 dp disc is the tallest of the band: it sits (62 - 16 - 36) / 2 = 5 dp below the 8 dp inset, so 13 dp from the top, and the address 2 dp under it.
        near(13f + 36f + 2f, line.top.value, "2 dp under the band")
        assertTrue(title.bottom <= description.top + 0.1.dp && description.bottom <= line.top, "title, description and address read top to bottom")
        val row = compose.onRoot().getBoundsInRoot()
        near(13f + 36f + 2f + 17.8f + 8f, row.bottom.value - row.top.value, "the row ends 8 dp under the address")
    }

    @Test
    fun aRowWithoutADescriptionHasTheAddressTwoDpUnderItsTitleAndKeepsItsMinimumHeight() {
        compose.setContent {
            ClearAirTheme {
                ClearAirRow(title = "Cold Storage", address = address, leading = { Box(Modifier.size(36.dp)) }, chevron = false)
            }
        }

        val line = compose.onNodeWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).getBoundsInRoot()
        val title = compose.onNodeWithText("Cold Storage", useUnmergedTree = true).getBoundsInRoot()
        near(68f, line.left.value, "the address starts where the text does")
        near(title.bottom.value + 2f, line.top.value, "2 dp under the title")
        val row = compose.onRoot().getBoundsInRoot()
        assertEquals(62f, row.bottom.value - row.top.value, 0.6f, "the row keeps its 62 dp")
    }

    @Test
    fun aRowWithAddressAndNoLeadingMarkStartsTheAddressAtTheGutter() {
        compose.setContent { ClearAirTheme { ClearAirRow(title = "Cold Storage", description = "Bitcoin", address = address, chevron = false) } }

        near(20f, compose.onNodeWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true).getBoundsInRoot().left.value, "the gutter")
    }
}
