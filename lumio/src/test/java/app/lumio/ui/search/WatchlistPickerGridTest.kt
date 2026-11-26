package app.lumio.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import app.lumio.ui.screenshot.PickerFixtures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows of Add to Watchlist sit on the mock's grid, measured in dp at the mock's own scale of 2 px a dp, where no length rounds: the first row starts
 * at 182 dp, a one-line row is 62 dp, a two-line name that ends in its rank makes 74 dp, three lines 94 dp, and a two-line name with no rank 73 dp. The
 * goldens are drawn at 1.75 px a dp, where each row rounds to whole pixels, so their dividers drift up to 0.6 dp from the mock's with the same layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchlistPickerGridTest {
    private val PHONE_TAG = "phone"


    @get:Rule
    val compose = createComposeRule()

    /** The top and the height of each row, in dp, drawn at 2 px a dp. */
    private fun rows(content: @Composable () -> Unit): List<Pair<Float, Float>> {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                ClearAirTheme { Box(Modifier.requiredSize(PickerFixtures.PHONE).testTag(PHONE_TAG)) { content() } }
            }
        }
        // The phone is larger than the test window, which centres it, so each top is read from the phone's own top.
        val phone = compose.onAllNodesWithTag(PHONE_TAG).fetchSemanticsNodes().single().boundsInRoot.top
        return compose.onAllNodesWithTag(PICKER_ROW_TAG).fetchSemanticsNodes().map { (it.boundsInRoot.top - phone) / 2f to it.boundsInRoot.height / 2f }
    }

    @Test
    fun theTopListStartsAt182AndEachRowIs62() {
        // The last row runs off the bottom of the phone, so only the rows wholly on it are measured.
        val rows = rows { PickerFixtures.Top() }.filter { (top, height) -> top + height < PickerFixtures.PHONE.height.value }

        assertEquals(182f, rows.first().first)
        assertEquals(List(rows.size) { 62f }, rows.map { it.second })
    }

    @Test
    fun aResultGrowsWithItsNameAndARankedLastLineIsOneDpTaller() {
        val rows = rows { PickerFixtures.Search() }

        assertEquals(182f, rows.first().first)
        assertEquals(listOf(74f, 74f, 74f, 74f, 94f, 73f), rows.map { it.second })
    }
}
