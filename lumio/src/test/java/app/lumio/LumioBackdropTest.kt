package app.lumio

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.CoverTheme
import app.lumio.network.NetworkMode
import app.lumio.ui.network.TESTNET_BANNER_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The testnet banner: it shows over every Lumio screen while the chosen side is testnet, and never while it is mainnet. */
@RunWith(RobolectricTestRunner::class)
class LumioBackdropTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mainnetShowsNoBanner() {
        setScreen(NetworkSelection())

        compose.onNodeWithText("Screen body").assertExists()
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun testnetShowsTheBannerAboveTheScreen() {
        setScreen(NetworkSelection().apply { mode = NetworkMode.TESTNET })

        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertExists()
        compose.onNodeWithText("Testnet").assertExists()
        compose.onNodeWithText("Screen body").assertExists()
    }

    @Test
    fun switchingTheSideShowsAndHidesTheBanner() {
        val selection = NetworkSelection()
        setScreen(selection)

        compose.runOnIdle { selection.mode = NetworkMode.TESTNET }
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertExists()

        compose.runOnIdle { selection.mode = NetworkMode.MAINNET }
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertDoesNotExist()
    }

    private fun setScreen(selection: NetworkSelection) {
        compose.setContent {
            CoverTheme {
                LumioBackdrop(selection) {
                    Text("Screen body")
                }
            }
        }
    }
}
