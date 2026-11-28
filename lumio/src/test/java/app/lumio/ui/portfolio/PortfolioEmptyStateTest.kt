package app.lumio.ui.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.Masking
import app.lumio.home.NativeAsset
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioHolding
import app.lumio.portfolio.holdingOf
import app.lumio.ui.home.AssetIconSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.ZoneId

/** The empty Portfolio (D3): the side's words, the other side's card when it holds funds, and the one action that fills the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class PortfolioEmptyStateTest {
    @get:Rule
    val compose = createComposeRule()

    private var switched = 0
    private var received = 0

    private fun show(
        testnet: Boolean,
        otherSide: OtherSide?,
        onReceive: (() -> Unit)?,
        showBalance: Boolean = true,
        holdings: List<PortfolioHolding> = emptyList(),
    ) {
        compose.setContent {
            ClearAirTheme {
                PortfolioScreen(
                    holdings = holdings,
                    filter = PortfolioFilter(),
                    onPick = {},
                    walletName = "Air 1",
                    showBalance = showBalance,
                    testnet = testnet,
                    zone = ZoneId.of("UTC"),
                    otherSide = otherSide,
                    onReceive = onReceive,
                )
            }
        }
    }

    @Test
    fun anEmptyMainnetWithTestnetFundsShowsTheCardAndTheReceiveCapsule() {
        show(
            testnet = false,
            otherSide = OtherSide(amount = "12.5 MON", network = "Monad Testnet", testnet = true, icon = AssetIconSpec.Letter("M"), onSwitch = { switched++ }),
            onReceive = { received++ },
        )

        compose.onNodeWithText("Nothing on Mainnet Yet").assertIsDisplayed()
        compose.onNodeWithText("12.5 MON on Testnet").assertIsDisplayed()
        compose.onNodeWithText("Monad Testnet · tap to switch sides").assertIsDisplayed()
        compose.onNodeWithText("Receive on Mainnet").assertIsDisplayed().performClick()
        assertEquals(1, received)
        compose.onNodeWithText("12.5 MON on Testnet").performClick()
        assertEquals(1, switched)
    }

    @Test
    fun aHiddenBalanceMasksTheOtherSideAmountAndKeepsItsSideAndNetwork() {
        show(
            testnet = false,
            otherSide = OtherSide(amount = "12.5 MON", network = "Monad Testnet", testnet = true, icon = AssetIconSpec.Letter("M"), onSwitch = { switched++ }),
            onReceive = null,
            showBalance = false,
        )

        compose.onNodeWithText("12.5 MON on Testnet").assertDoesNotExist()
        compose.onNodeWithText("${Masking.MASK} on Testnet").assertIsDisplayed()
        compose.onNodeWithText("Monad Testnet · tap to switch sides").assertIsDisplayed()
    }

    @Test
    fun anEmptyTestnetWithoutFundsOnTheOtherSideShowsNeitherTheCardNorTheAction() {
        show(testnet = true, otherSide = null, onReceive = null)

        compose.onNodeWithText("Nothing on Testnet Yet").assertIsDisplayed()
        compose.onNodeWithText("tap to switch sides", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Receive on Testnet").assertDoesNotExist()
    }

    private fun coin(amount: String, usd: Double?, testnet: Boolean = true): PortfolioHolding {
        val network = NetworkId(Networks.evm(11155111), testnet = testnet)
        return holdingOf(NativeAsset(id = NativeAsset.idOf(network), symbol = "ETH", name = "Ethereum", network = network, balance = BigDecimal(amount), usd = usd))
    }

    @Test
    fun fundsWithNoPriceShowTheirRowsNotTheEmptyState() {
        // Testnet coins never have a price, and a mainnet coin has none until a source answers: the funds are there, only their value is not.
        show(testnet = true, otherSide = null, onReceive = null, holdings = listOf(coin("15", usd = null)))

        compose.onNodeWithText("Nothing on Testnet Yet").assertDoesNotExist()
        compose.onNodeWithText("15 ETH").assertIsDisplayed()
    }

    @Test
    fun zeroBalancesWithNoPriceStayEmpty() {
        show(testnet = true, otherSide = null, onReceive = null, holdings = listOf(coin("0", usd = null)))

        compose.onNodeWithText("Nothing on Testnet Yet").assertIsDisplayed()
    }
}
