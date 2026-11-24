package app.lumio.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Data providers screen: each provider's switch, its Details action, its order actions, and the Prefer RPC switch, and what each reports back.
 * The window is tall enough that every row is on screen, so a tap reaches the control it names. The key of a provider is set on its Provider page.
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
    private val opened = mutableListOf<String>()
    private val preferred = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent {
            ClearAirTheme {
                DataProvidersScreen(
                    data = listOf(etherscan, mempool),
                    rpc = listOf(oneRpc),
                    offline = null,
                    onBack = {},
                    onLock = {},
                    onOpen = { opened += it },
                    onEnabled = { id, on -> switched += id to on },
                    onMove = { id, up -> moved += id to up },
                    onPreferRpc = { preferred += it },
                )
            }
        }
    }

    @Test
    fun aSwitchTurnsItsProviderOnAndOff() {
        show()
        compose.onNodeWithTag("enable-mempool").assertIsOn().performClick()
        assertEquals(listOf("mempool" to false), switched)
    }

    @Test
    fun orderActionsAreHeldAtTheEndsOfTheirGroup() {
        show()
        compose.onNodeWithTag("move-up-etherscan").assertIsNotEnabled()
        compose.onNodeWithTag("move-down-mempool").assertIsNotEnabled()
        compose.onNodeWithTag("move-down-etherscan").assertIsEnabled().performClick()
        assertEquals(listOf("etherscan" to false), moved)
    }

    @Test
    fun detailsOpensTheProvidersPage() {
        show()
        compose.onNodeWithTag("open-1rpc").performClick()
        assertEquals(listOf("1rpc"), opened)
    }

    @Test
    fun thePreferRpcSwitchReportsItsNewValue() {
        show()
        compose.onNodeWithTag("prefer-rpc").assertIsOff().performClick()
        assertEquals(listOf(true), preferred)
    }
}
