package app.lumio

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.CoverTheme
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.TESTNET_HATCH_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The testnet band: it shows over every Lumio screen while the chosen side is testnet, and never while it is mainnet, unless the screen is a
 * test-side page of its own.
 */
@RunWith(RobolectricTestRunner::class)
class LumioBackdropTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mainnetShowsNoHatch() {
        setScreen(NetworkSelection())

        compose.onNodeWithText("Screen body").assertExists()
        compose.onNodeWithTag(TESTNET_HATCH_TAG).assertDoesNotExist()
    }

    @Test
    fun testnetShowsTheHatchAboveTheScreen() {
        setScreen(NetworkSelection().apply { mode = NetworkMode.TESTNET })

        compose.onNodeWithTag(TESTNET_HATCH_TAG).assertExists()
        compose.onNodeWithText("TESTNET · TEST FUNDS HAVE NO VALUE").assertExists()
        compose.onNodeWithText("Screen body").assertExists()
    }

    @Test
    fun switchingTheSideShowsAndHidesTheHatch() {
        val selection = NetworkSelection()
        setScreen(selection)

        compose.runOnIdle { selection.mode = NetworkMode.TESTNET }
        compose.onNodeWithTag(TESTNET_HATCH_TAG).assertExists()

        compose.runOnIdle { selection.mode = NetworkMode.MAINNET }
        compose.onNodeWithTag(TESTNET_HATCH_TAG).assertDoesNotExist()
    }

    /** A test-side page shows the hatch on mainnet too, because the page is about the test networks. */
    @Test
    fun testSidePageShowsTheHatchOnMainnet() {
        compose.setContent {
            CoverTheme {
                LumioBackdrop(NetworkSelection(), testSide = true) {
                    Text("Screen body")
                }
            }
        }

        compose.onNodeWithTag(TESTNET_HATCH_TAG).assertExists()
    }

    /** A chosen side is handed to the store at once, which keeps it in the vault. */
    @Test
    fun choosingASideHandsItToTheStore() {
        val stored = mutableListOf<NetworkMode>()
        val selection = NetworkSelection { stored += it }

        selection.choose(NetworkMode.TESTNET)

        assertEquals(listOf(NetworkMode.TESTNET), stored)
        assertEquals(NetworkMode.TESTNET, selection.mode)
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
