package app.lumio.ui.asset

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.airvault.core.model.AccountId
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Holdings Breakdown: each chain heads its accounts with its total, and an account row opens that account. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldingsPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val cold = AccountHolding(
        walletId = "wallet-1",
        accountId = AccountId("eip155:1:0x" + "a1".repeat(20)),
        chain = "Ethereum",
        name = "Cold Storage",
        detail = "Air 1 #11",
        address = "0x" + "a1".repeat(20),
        amount = BigDecimal("2"),
    )

    @Test
    fun aChainHeadsItsAccountsWithItsTotal() {
        val opened = mutableListOf<AccountHolding>()
        val chains = listOf(ChainHolding(chain = "Ethereum", network = NetworkId(Networks.evm(1L), testnet = false), total = BigDecimal("3"), accounts = listOf(cold)))
        compose.setContent {
            ClearAirTheme {
                HoldingsPage(
                    title = "Your ETH",
                    symbol = "ETH",
                    chains = chains,
                    showBalance = true,
                    onAccount = { opened += it },
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Your ETH").assertIsDisplayed()
        compose.onNodeWithText("ETHEREUM").assertIsDisplayed()
        compose.onNodeWithText("3 ETH").assertIsDisplayed()
        compose.onNodeWithText("Cold Storage").performClick()

        assertEquals(listOf(cold), opened)
    }
}
