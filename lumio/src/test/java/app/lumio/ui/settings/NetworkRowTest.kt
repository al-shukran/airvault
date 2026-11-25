package app.lumio.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.evm.EvmChain
import app.lumio.evm.EvmSettings
import app.lumio.ui.screenshot.LUMIO_DARK_QUALIFIERS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A network row is 62 dp high, as the mockup draws it, and its switch keeps a 48 dp touch target inside that height. Native graphics give the text its real height. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRowIs62DpHighAndItsSwitchIs48Dp() {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    Box(Modifier.testTag("row")) {
                        ChainRow(
                            chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null),
                            settings = EvmSettings(enabled = setOf(1L)),
                            logo = null,
                            busy = false,
                            onEnabled = {},
                            onNetwork = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("row").assertHeightIsEqualTo(62.dp)
        compose.onNode(hasContentDescription("Ethereum network")).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
