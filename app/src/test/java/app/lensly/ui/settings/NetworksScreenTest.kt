package app.lensly.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.shippedRegistry
import app.lensly.ui.theme.LenslyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Networks list: every network of the catalog, the filters, and a card that opens its fee model and token count. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class NetworksScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val mainnet = NetworkEntry(chainId = 1L, name = "Alpha Chain", nativeSymbol = "ETH", feeModel = FeeModel.L1, explorerName = null, testnet = false)
    private val mainnetTwo = NetworkEntry(chainId = 10L, name = "Beta Rollup", nativeSymbol = "ETH", feeModel = FeeModel.L2_ROLLUP, explorerName = null, testnet = false)
    private val testnet = NetworkEntry(chainId = 11155111L, name = "Gamma Testnet", nativeSymbol = "SEP", feeModel = FeeModel.L1, explorerName = null, testnet = true)
    private val testnetTwo = NetworkEntry(chainId = 84532L, name = "Delta Testnet", nativeSymbol = "ETH", feeModel = FeeModel.L2_ROLLUP, explorerName = null, testnet = true)
    private val networks = listOf(mainnet, mainnetTwo, testnet, testnetTwo)

    private fun show(networks: List<NetworkEntry>, tokens: List<TokenEntry> = emptyList()) {
        compose.setContent {
            LenslyTheme {
                NetworksScreen(networks = networks, tokens = tokens, onBack = {}, onLock = {})
            }
        }
    }

    @Test
    fun listsEveryNetworkOfTheRegistry() {
        val registry = shippedRegistry()
        show(registry.networks(), registry.tokens())

        compose.onNodeWithText(registry.networks().first().name).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(registry.networks().chunked(2).lastIndex)
        compose.onNodeWithText(registry.networks().last().name).assertExists()
    }

    @Test
    fun testnetsFilterHidesTheMainnets() {
        show(networks)

        compose.onNodeWithText("Testnets").performClick()

        compose.onNodeWithText(mainnet.name).assertDoesNotExist()
        compose.onNodeWithText(mainnetTwo.name).assertDoesNotExist()
        compose.onNodeWithText(testnet.name).assertIsDisplayed()
        compose.onNodeWithText(testnetTwo.name).assertIsDisplayed()
    }

    @Test
    fun mainnetsFilterHidesTheTestnets() {
        show(networks)

        compose.onNodeWithText("Mainnets").performClick()

        compose.onNodeWithText(testnet.name).assertDoesNotExist()
        compose.onNodeWithText(mainnet.name).assertIsDisplayed()
        compose.onNodeWithText(mainnetTwo.name).assertIsDisplayed()
    }

    @Test
    fun aTestnetCardIsMarkedTestnet() {
        show(networks)

        compose.onAllNodesWithText("TESTNET").assertCountEquals(2)
    }

    @Test
    fun aTapOpensTheFeeModelAndTheTokenCountOfTheNetwork() {
        val tokens = List(3) { index ->
            TokenEntry(chainId = testnet.chainId, contract = "0x" + "%040x".format(index + 1), symbol = "T$index", name = "Token $index", decimals = 18)
        }
        show(networks, tokens)
        compose.onNodeWithText("Fee model").assertDoesNotExist()

        compose.onNodeWithText(testnet.name).performClick()

        compose.onNodeWithText("Fee model").assertIsDisplayed()
        compose.onNodeWithText("3 tokens").assertIsDisplayed()
    }
}
