package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.explore.ExploreScreen
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The golden of the Explore landing: the chains group, and the data providers with their status words. The providers are fixed here, so the
 * golden does not move with the schedulers. Recorded with `:lumio:recordRoborazziDebug`, like [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun explore() = compose.lumioSnapshot("explore") {
        ExploreScreen(
            providers = listOf(
                ProviderRow("blockscout", false, true, true, null, ProviderStatus.Healthy(score = 120.0), false, true),
                ProviderRow("mempool", false, false, true, null, ProviderStatus.CoolingDown(inMs = 4_000), true, false),
                ProviderRow("etherscan", false, true, true, null, ProviderStatus.NeedsKey, true, true),
            ),
            onChains = {},
            onProviders = {},
            onProvider = {},
        )
    }
}
