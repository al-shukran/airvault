package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Data providers screen: its two groups, each row's switch, order, status, MEV badge and key field, and what it reports back. The window is tall
 * enough that every row is on screen, so a tap reaches the control it names. Buttons and badges print their text in capitals, so the text matches ignore case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class DataProvidersScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val etherscan = ProviderRow(
        id = "etherscan",
        mevProtected = false,
        acceptsKey = true,
        enabled = true,
        apiKey = null,
        status = ProviderStatus.NeedsKey,
        canMoveUp = false,
        canMoveDown = true,
    )
    private val mempool = ProviderRow(
        id = "mempool",
        mevProtected = false,
        acceptsKey = false,
        enabled = true,
        apiKey = null,
        status = ProviderStatus.Ready,
        canMoveUp = true,
        canMoveDown = false,
    )
    private val oneRpc = ProviderRow(
        id = "1rpc",
        mevProtected = true,
        acceptsKey = true,
        enabled = true,
        apiKey = "k-1",
        status = ProviderStatus.Healthy(score = 120.0),
        canMoveUp = true,
        canMoveDown = true,
    )

    private val switched = mutableListOf<Pair<String, Boolean>>()
    private val moved = mutableListOf<Pair<String, Boolean>>()
    private val keys = mutableListOf<Pair<String, String>>()

    private fun show() {
        compose.setContent {
            LenslyTheme {
                DataProvidersScreen(
                    data = listOf(etherscan, mempool),
                    rpc = listOf(oneRpc),
                    onBack = {},
                    onEnabled = { id, on -> switched += id to on },
                    onMove = { id, up -> moved += id to up },
                    onApiKey = { id, key -> keys += id to key },
                )
            }
        }
    }

    @Test
    fun theDataAndRpcGroupsRenderTheirProviders() {
        show()

        compose.onNodeWithText("Data", ignoreCase = true).assertExists()
        compose.onNodeWithText("RPC", ignoreCase = true).assertExists()
        compose.onNodeWithText("etherscan").assertExists()
        compose.onNodeWithText("mempool").assertExists()
        compose.onNodeWithText("1rpc").assertExists()
    }

    @Test
    fun theMevBadgeShowsOnlyOnAProtectedProvider() {
        show()

        compose.onAllNodesWithText("MEV-protected", ignoreCase = true).assertCountEquals(1)
    }

    @Test
    fun eachProviderShowsItsStatus() {
        show()

        compose.onNodeWithText("Needs key").assertExists()
        compose.onNodeWithText("Healthy, score 120").assertExists()
        compose.onNodeWithText("Ready, no calls yet").assertExists()
    }

    @Test
    fun aKeyFieldShowsOnlyForAProviderThatTakesAKey() {
        show()

        compose.onAllNodesWithText("Save key", ignoreCase = true).assertCountEquals(2)
        compose.onNodeWithTag("key-mempool").assertDoesNotExist()
    }

    @Test
    fun aSwitchReportsTheProviderAndTheNewState() {
        show()

        compose.onNodeWithTag("enable-mempool").performClick()

        assertEquals(listOf("mempool" to false), switched)
    }

    @Test
    fun theMoveButtonsAreOffAtTheEndsOfAGroup() {
        show()

        compose.onNodeWithTag("move-up-etherscan").assertIsNotEnabled()
        compose.onNodeWithTag("move-down-mempool").assertIsNotEnabled()
        compose.onNodeWithTag("move-down-etherscan").assertIsEnabled()

        compose.onNodeWithTag("move-down-etherscan").performClick()

        assertEquals(listOf("etherscan" to false), moved)
    }

    @Test
    fun aTypedKeyIsSavedAndClearedKeyIsReportedAsBlank() {
        show()

        compose.onNodeWithTag("key-etherscan").performTextReplacement("k-99")
        compose.onNodeWithTag("save-key-etherscan").performClick()
        compose.onNodeWithTag("clear-key-1rpc").performClick()

        assertEquals(listOf("etherscan" to "k-99", "1rpc" to ""), keys)
    }

    @Test
    fun theKeyIsHiddenUntilItsRevealToggleIsTapped() {
        show()

        compose.onNodeWithTag("reveal-1rpc").assertIsOff().performClick()

        compose.onNodeWithTag("reveal-1rpc").assertIsOn()
    }
}
