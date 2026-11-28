package app.lumio.ui.network

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import app.lumio.ui.screenshot.LUMIO_LIGHT_QUALIFIERS
import app.lumio.ui.screenshot.lumioSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePanelScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panelOpen() = compose.lumioSnapshot("profile_panel", frame = FRAME) {
        Panel()
    }

    @Test
    @Config(qualifiers = LUMIO_DARK_QUALIFIERS)
    fun panelOpenDark() = compose.lumioSnapshot("profile_panel_dark", frame = FRAME) {
        Panel()
    }

    /** The panel opens over Home on the Testnet side, so the scrim covers the testnet band at the top. The panel covers the band's label, so the band has none here. */
    @Composable
    private fun Panel() = Box {
        ClearAirTestnetHatch(text = "")
        ProfilePanel(
            visible = true,
            mode = NetworkMode.TESTNET,
            testNetworks = 4,
            networkSentence = testNetworkSentence(listOf("Monad Testnet")),
            wallets = WALLETS,
            activeWalletId = "air1",
            onMode = {},
            onScan = {},
            onTestNetworks = {},
            onWallet = {},
            onPair = {},
            onDismiss = {},
        )
    }

    private companion object {
        /** The approved frames' size, 360 x 740 dp, so a golden compares with its frame one to one (scripts/design/measure-bands.py). */
        val FRAME = DpSize(360.dp, 740.dp)
    }
}
