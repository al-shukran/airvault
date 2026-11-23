package app.lumio.ui.network

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.network.NetworkMode
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
    fun panelOpen() = compose.lumioSnapshot("profile_panel") {
        Panel()
    }

    @Composable
    private fun Panel() {
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
}
