package app.lumio.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
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
 * The Data providers screen: each row is numbered within its group and opens its Provider page, and the Prefer RPC switch reports its new value. The
 * window is tall enough that every row is on screen, so a tap reaches the row it names. The switch and the key are set on the Provider page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class DataProvidersScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val etherscan = ProviderRow(
        id = "etherscan",
        acceptsKey = true,
        enabled = true,
        apiKey = null,
        status = ProviderStatus.NeedsKey,
    )
    private val mempool = ProviderRow(
        id = "mempool",
        acceptsKey = false,
        enabled = true,
        apiKey = null,
        status = ProviderStatus.Ready,
    )
    private val routescan = mempool.copy(id = "routescan")
    private val oneRpc = ProviderRow(
        id = "1rpc",
        acceptsKey = true,
        enabled = true,
        apiKey = "k-1",
        status = ProviderStatus.Healthy(score = 120.0),
    )

    private val opened = mutableListOf<String>()
    private val reordered = mutableListOf<List<String>>()
    private val preferred = mutableListOf<Boolean>()

    private fun show() {
        compose.setContent {
            ClearAirTheme {
                DataProvidersScreen(
                    data = listOf(etherscan, routescan, mempool),
                    rpc = listOf(oneRpc),
                    offline = null,
                    onBack = {},
                    onLock = {},
                    onOpen = { opened += it },
                    onReorder = { _, order -> reordered += order },
                    onPreferRpc = { preferred += it },
                )
            }
        }
    }

    @Test
    fun aRowOpensItsProviderPage() {
        show()
        compose.onNodeWithTag("provider-mempool").performClick()
        assertEquals(listOf("mempool"), opened)
    }

    @Test
    fun aRowInAnyGroupOpensItsProviderPage() {
        show()
        compose.onNodeWithTag("provider-1rpc").performClick()
        assertEquals(listOf("1rpc"), opened)
    }

    @Test
    fun thePreferRpcSwitchReportsItsNewValue() {
        show()
        compose.onNodeWithTag("prefer-rpc").assertIsOff().performClick()
        assertEquals(listOf(true), preferred)
    }

    @Test
    fun aRowOffersMoveDownForTalkBackAndMovesOneSlot() {
        show()
        val actions = compose.onNodeWithTag("provider-etherscan").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move Down"), actions.map { it.label })
        compose.runOnIdle { actions.single().action() }
        assertEquals(listOf(listOf("routescan", "etherscan", "mempool")), reordered)
    }

    @Test
    fun theLastRowOffersOnlyMoveUp() {
        show()
        val actions = compose.onNodeWithTag("provider-routescan").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move Up"), actions.map { it.label })
    }
}
