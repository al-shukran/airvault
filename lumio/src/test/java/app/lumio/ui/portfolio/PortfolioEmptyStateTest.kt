package app.lumio.ui.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.portfolio.PortfolioFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** The empty Portfolio (D3): the side's words, the other side's card when it holds funds, and the one action that fills the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class PortfolioEmptyStateTest {
    @get:Rule
    val compose = createComposeRule()

    private var switched = 0
    private var received = 0

    private fun show(testnet: Boolean, otherSide: OtherSide?, onReceive: (() -> Unit)?) {
        compose.setContent {
            ClearAirTheme {
                PortfolioScreen(
                    holdings = emptyList(),
                    filter = PortfolioFilter(),
                    onFilter = {},
                    walletName = "Air 1",
                    accounts = emptyMap(),
                    showBalance = true,
                    testnet = testnet,
                    recent = emptyList(),
                    names = emptyMap(),
                    zone = ZoneId.of("UTC"),
                    onOpenActivity = {},
                    onOpenRow = {},
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
            otherSide = OtherSide(amount = "12.5 MON", network = "Monad Testnet", testnet = true, onSwitch = { switched++ }),
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
    fun anEmptyTestnetWithoutFundsOnTheOtherSideShowsNeitherTheCardNorTheAction() {
        show(testnet = true, otherSide = null, onReceive = null)

        compose.onNodeWithText("Nothing on Testnet Yet").assertIsDisplayed()
        compose.onNodeWithText("tap to switch sides", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Receive on Testnet").assertDoesNotExist()
    }
}
