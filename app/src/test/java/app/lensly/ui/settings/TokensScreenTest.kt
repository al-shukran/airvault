package app.lensly.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Tokens list: tokens grouped under their network, the network filter, and the full contract of each token. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class TokensScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val alpha = NetworkEntry(chainId = 1L, name = "Alpha Chain", nativeSymbol = "ETH", feeModel = FeeModel.L1, explorerName = null, testnet = false)
    private val beta = NetworkEntry(chainId = 10L, name = "Beta Rollup", nativeSymbol = "ETH", feeModel = FeeModel.L2_ROLLUP, explorerName = null, testnet = false)
    private val alphaOne = TokenEntry(chainId = 1L, contract = "0x" + "1".repeat(40), symbol = "AAA", name = "Alpha Token", decimals = 18)
    private val alphaTwo = TokenEntry(chainId = 1L, contract = "0x" + "2".repeat(40), symbol = "BBB", name = "Bravo Token", decimals = 6)
    private val betaOne = TokenEntry(chainId = 10L, contract = "0x" + "3".repeat(40), symbol = "CCC", name = "Charlie Token", decimals = 8)

    private fun show() {
        compose.setContent {
            LenslyTheme {
                TokensScreen(
                    networks = listOf(alpha, beta),
                    tokens = listOf(alphaOne, alphaTwo, betaOne),
                    onBack = {},
                    onLock = {},
                )
            }
        }
    }

    @Test
    fun tokensAreGroupedUnderTheirNetworkWithItsCount() {
        show()

        compose.onNodeWithText("Alpha Chain").assertIsDisplayed()
        compose.onNodeWithText("2 tokens").assertIsDisplayed()
        compose.onNodeWithText("Beta Rollup").assertIsDisplayed()
        compose.onNodeWithText("1 token").assertIsDisplayed()

        val betaHeader = compose.onNodeWithText("Beta Rollup").fetchSemanticsNode().boundsInRoot.top
        val alphaRow = compose.onNodeWithText("AAA").fetchSemanticsNode().boundsInRoot.top
        val betaRow = compose.onNodeWithText("CCC").fetchSemanticsNode().boundsInRoot.top
        assertTrue(alphaRow < betaHeader, "AAA belongs to the Alpha group, above the Beta header")
        assertTrue(betaRow > betaHeader, "CCC belongs to the Beta group, below its header")
    }

    @Test
    fun eachTokenShowsItsFullContractInGroupsOfFour() {
        show()

        compose.onNodeWithText(groupedAddress(alphaOne.contract)).assertIsDisplayed()
        compose.onNodeWithText(groupedAddress(betaOne.contract)).assertIsDisplayed()
        assertEquals("0x1111 1111 1111 1111 1111\n1111 1111 1111 1111 1111", groupedAddress(alphaOne.contract))
    }

    @Test
    fun aNetworkChipShowsOnlyThatNetworksTokens() {
        show()

        compose.onNodeWithContentDescription("Beta Rollup").performClick()

        compose.onNodeWithText("AAA").assertDoesNotExist()
        compose.onNodeWithText("Alpha Chain").assertDoesNotExist()
        compose.onNodeWithText("CCC").assertExists()
    }
}
