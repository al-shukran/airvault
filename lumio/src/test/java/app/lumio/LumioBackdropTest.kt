package app.lumio

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.CoverTheme
import app.lumio.network.NetworkMode
import app.lumio.ui.network.TESTNET_BANNER_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The testnet strip: it shows over every Lumio screen while the chosen side is testnet, and never while it is mainnet. */
@RunWith(RobolectricTestRunner::class)
class LumioBackdropTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mainnetShowsNoStrip() {
        setScreen(NetworkSelection())

        compose.onNodeWithText("Screen body").assertExists()
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun testnetShowsTheStripAboveTheScreen() {
        setScreen(NetworkSelection().apply { mode = NetworkMode.TESTNET })

        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertExists()
        compose.onNodeWithText("Testnet").assertExists()
        compose.onNodeWithText("Screen body").assertExists()
    }

    @Test
    fun switchingTheSideShowsAndHidesTheStrip() {
        val selection = NetworkSelection()
        setScreen(selection)

        compose.runOnIdle { selection.mode = NetworkMode.TESTNET }
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertExists()

        compose.runOnIdle { selection.mode = NetworkMode.MAINNET }
        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertDoesNotExist()
    }

    /** The strip opens the side sheet, and a choice in it applies at once: the strip goes, and the selection holds the new side. */
    @Test
    fun stripOpensTheSideSheetAndChoosingMainnetAppliesAtOnce() {
        val selection = NetworkSelection().apply { mode = NetworkMode.TESTNET }
        setScreen(selection)

        compose.onNodeWithTag(TESTNET_BANNER_TAG).performClick()
        compose.onNodeWithText("Mainnet").performClick()

        compose.onNodeWithTag(TESTNET_BANNER_TAG).assertDoesNotExist()
        assertEquals(NetworkMode.MAINNET, selection.mode)
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
