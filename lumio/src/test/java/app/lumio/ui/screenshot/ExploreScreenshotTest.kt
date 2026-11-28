package app.lumio.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.lumio.network.NetworkMode
import app.lumio.ui.explore.ChainRow
import app.lumio.ui.explore.ExploreScreen
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import app.lumio.ui.shell.LumioRootScreen
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
    fun explore() = compose.lumioSnapshot("explore", frame = DpSize(360.dp, 740.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) { Shell { Explore() } }
            Spacer(Modifier.height(GESTURE_AREA))
        }
    }

    /** The Explore tab in its shell, as the mock draws it: the root top bar with the title, the tab bar under the content. */
    @Composable
    private fun Shell(content: @Composable ColumnScope.() -> Unit) = LumioRootScreen(
        tab = LumioTab.EXPLORE,
        mode = NetworkMode.MAINNET,
        onMode = {},
        panelWallets = emptyList(),
        activeWalletId = null,
        onWallet = {},
        testNetworks = 0,
        networkSentence = "",
        offline = null,
        onTab = {},
        onScan = {},
        onTestNetworks = {},
        onPair = {},
        onLock = {},
        search = { _ -> },
        content = content,
    )

    @Composable
    private fun Explore() {
        ExploreScreen(
            chains = listOf(
                ChainRow("Ethereum", "Layer 1 · 4 of your accounts", AssetIconSpec.Chain(null)),
                ChainRow("Base", "L2 on Ethereum · ID 8453", AssetIconSpec.Chain(null)),
                ChainRow("Solana", "Layer 1 · 1 of your accounts", AssetIconSpec.Solana),
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

    private companion object {
        /** The mock's gesture area under the tab bar, which a phone's navigation inset gives it. */
        val GESTURE_AREA = 24.dp
    }
}
