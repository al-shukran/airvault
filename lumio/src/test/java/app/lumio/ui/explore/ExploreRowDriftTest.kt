package app.lumio.ui.explore

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.ui.home.AssetIconSpec
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rows keep their designed spacing at a fractional density (#1742). A row is 62 dp, which is 108.5 px at 1.75 px a dp; drawn as 109 px each, the rows of a
 * group drift down by half a pixel per row. The rows of a group must stay within a pixel of the cumulative 62 dp spacing, however many there are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreRowDriftTest {
    @get:Rule
    val compose = createComposeRule()

    private val density = 1.75f
    private val titles = List(10) { "Chain $it" }

    @Test
    fun theRowsOfAGroupStayWithinAPixelOfTheirDesignedSpacingAtOnePointSevenFiveDensity() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                ClearAirTheme {
                    ExploreScreen(
                        chains = titles.map { ChainRow(it, "Layer 1", AssetIconSpec.Bitcoin) },
                        networks = titles.size,
                        providers = emptyList(),
                        providerCount = 0,
                        onChains = {},
                        onProviders = {},
                        onProvider = {},
                        onArticle = {},
                    )
                }
            }
        }

        val tops = titles.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        titles.indices.forEach { i ->
            val drift = abs((tops[i] - tops[0]) - i * 62f * density)
            assertTrue(drift <= 1f, "row $i is $drift px from where 62 dp rows put it")
        }
    }
}
