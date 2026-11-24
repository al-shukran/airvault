package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.explore.ChainRow
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
            chains = listOf(
                ChainRow("Ethereum", "Layer 1 · 4 of your accounts"),
                ChainRow("Base", "L2 on Ethereum · ID 8453"),
                ChainRow("Solana", "Layer 1 · 1 of your accounts"),
            ),
            networks = 55,
            providers = listOf(
                ProviderRow("blockscout", true, true, null, ProviderStatus.Healthy(score = 120.0)),
                ProviderRow("mempool", false, true, null, ProviderStatus.CoolingDown(inMs = 4_000)),
                ProviderRow("etherscan", true, true, null, ProviderStatus.NeedsKey),
            ),
            onChains = {},
            onProviders = {},
            onProvider = {},
        )
    }
}
