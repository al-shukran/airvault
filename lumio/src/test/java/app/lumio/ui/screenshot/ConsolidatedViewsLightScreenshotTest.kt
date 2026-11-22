package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.portfolio.PortfolioFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The light-theme goldens of the consolidated views: the Portfolio balances, the activity and an address sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsolidatedViewsLightScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun portfolioBalancesLight() = compose.lumioSnapshot("portfolio_balances_light") { PortfolioSample(PortfolioFilter()) }

    @Test
    fun activityListLight() = compose.lumioSnapshot("activity_list_light") { ActivitySample() }

    @Test
    fun addressKnownLight() = compose.lumioSnapshot("address_known_light") { AddressSheetSample(known = true) }
}
