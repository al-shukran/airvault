package app.lumio.ui.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioHolding
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Portfolio's balance list under Hide Small Balances: a list whose every balance is left out says how many, and Show lists them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class PortfolioHiddenSmallTest {
    @get:Rule
    val compose = createComposeRule()

    private val dust = PortfolioHolding(
        coin = "DUST",
        title = "Dust Token",
        network = NetworkId(Networks.evm(1L), false),
        amount = BigDecimal("3"),
        usd = 0.3,
        change24h = null,
        mergeKey = null,
        bridged = false,
    )

    private fun show(holdings: List<PortfolioHolding>) {
        compose.setContent {
            ClearAirTheme {
                PortfolioScreen(
                    holdings = holdings,
                    filter = PortfolioFilter(),
                    onPick = {},
                    walletName = "Air 1",
                    showBalance = true,
                    hidesSmall = { usd -> usd != null && usd < 1.0 },
                    testnet = false,
                    zone = ZoneId.of("UTC"),
                )
            }
        }
    }

    @Test
    fun aPortfolioWhoseEveryBalanceIsSmallSaysHowManyAreHiddenAndShowListsThem() {
        show(listOf(dust))

        compose.onNodeWithText("Dust Token").assertDoesNotExist()
        compose.onNodeWithText("1 small balance hidden").assertIsDisplayed()

        compose.onNodeWithText("Show").performClick()

        compose.onNodeWithText("Dust Token").assertIsDisplayed()
        compose.onNodeWithText("1 small balance hidden").assertDoesNotExist()
    }
}
