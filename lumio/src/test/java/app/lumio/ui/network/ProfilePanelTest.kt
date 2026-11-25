package app.lumio.ui.network

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.shell.LumioRootScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The profile panel, opened from the root of a tab: its rows in the mock's order, the Lensly wallets as radio rows, and the wallet a radio choice sets
 * as the one the tab shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePanelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun panelOpensFromTheRootWithItsRowsInTheMockOrder() {
        compose.setContent { Shell() }
        openPanel()

        val rows = listOf(
            compose.onNodeWithText("Scan to Pay"),
            compose.onNodeWithText("Networks", ignoreCase = true),
            compose.onNodeWithText("Choose Test Networks"),
            compose.onNodeWithText("Lensly Wallets", ignoreCase = true),
            row("Air 1"),
            row("Air 2"),
            compose.onNodeWithText("Pair Another Wallet"),
        )
        val tops = rows.map { it.fetchSemanticsNode().positionInRoot.y }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Send to a scanned address").assertIsDisplayed()
        compose.onNodeWithText("Mainnet").assertIsDisplayed()
        compose.onNodeWithText("Testnet").assertIsDisplayed()
    }

    @Test
    fun choosingAWalletSetsTheScopeAndTheRadio() {
        compose.setContent { Shell() }
        openPanel()
        row("Air 1").assertIsSelected()
        row("Air 2").assertIsNotSelected()
        compose.onNodeWithText("Scope: air1").assertIsDisplayed()

        row("Air 2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scope: air2").assertIsDisplayed()

        openPanel()
        row("Air 2").assertIsSelected()
        row("Air 1").assertIsNotSelected()
    }

    @Test
    fun scanToPayClosesThePanelAndRunsItsAction() {
        compose.setContent { Shell() }
        openPanel()
        compose.onNodeWithText("Scan to Pay").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scanned: air1").assertIsDisplayed()
    }

    @Test
    fun scanToPayTellsTheOwnerToTurnOnANetworkAndOpensNothingWhileNoneIsOn() {
        compose.setContent { Shell(scanNote = "Turn on a network to scan to pay") }
        openPanel()
        compose.onNodeWithText("Turn on a network to scan to pay").assertIsDisplayed()
        compose.onNodeWithText("Scan to Pay").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Turn on a network to scan to pay").assertIsDisplayed()
        compose.onAllNodesWithText("Scanned: air1").assertCountEquals(0)
    }

    @Test
    fun scanToPayOpensNothingBeforeTheSettingsAreReadEvenOnATap() {
        compose.setContent { Shell(scanReady = false) }
        openPanel()
        compose.onNodeWithText("Scan to Pay").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Scanned: air1").assertCountEquals(0)
    }

    private fun openPanel() {
        compose.onAllNodesWithContentDescription("Open the profile panel").onFirst().performClick()
        compose.waitForIdle()
    }

    /** The wallet row named [name]: the radio row whose text starts with the name. */
    private fun row(name: String) = compose.onNode(isSelectable() and hasText(name, substring = true))

    @Composable
    private fun Shell(scanNote: String? = null, scanReady: Boolean = true) {
        LumenTheme {
            ClearAirTheme {
                Root(scanNote, scanReady)
            }
        }
    }

    @Composable
    private fun Root(scanNote: String?, scanReady: Boolean) {
        var active by remember { mutableStateOf("air1") }
        var scanned by remember { mutableStateOf<String?>(null) }
        LumioRootScreen(
            tab = LumioTab.HOME,
            title = WALLETS.first { it.id == active }.name,
            mode = NetworkMode.MAINNET,
            onMode = {},
            panelWallets = WALLETS,
            activeWalletId = active,
            onWallet = { active = it },
            testNetworks = 4,
            networkSentence = testNetworkSentence(listOf("Monad Testnet")),
            offline = null,
            onTab = {},
            onScan = { scanned = active },
            scanNote = scanNote,
            scanReady = scanReady,
            onTestNetworks = {},
            onPair = {},
            onLock = {},
            search = { _ -> },
            content = {
                Text("Scope: $active")
                scanned?.let { Text("Scanned: $it") }
            },
        )
    }
}

/** The two Lensly wallets that the panel tests list, as the mock names them. */
internal val WALLETS = listOf(
    PanelWallet("air1", "Air 1", "30 accounts · paired 21 Nov"),
    PanelWallet("air2", "Air 2", "3 accounts · paired 18 Nov", WalletEmblemTone.STONE),
)
