package app.lumio.ui.wallets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.wallets.WalletAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The wallet page (C2): the change under its total, and the page notice that stays up while a chain that did not answer names itself under its island. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ethereum: ChainId = Networks.evm(1L)

    private fun render(
        changePct: Double? = null,
        changeUsd: Double? = null,
        notice: String? = null,
        unreachable: Set<ChainId> = emptySet(),
    ) {
        compose.setContent {
            ClearAirTheme {
                WalletScreen(
                    mode = NetworkMode.MAINNET,
                    accounts = listOf(
                        WalletAccount(
                            walletId = "wallet-demo",
                            id = AccountId.of(ethereum, "0x" + "%040x".format(1)),
                            index = 0,
                            networkName = "Ethereum",
                            coin = null,
                            balance = null,
                        ),
                    ),
                    tokenCounts = emptyMap(),
                    totalUsd = 1_000.0,
                    changePct = changePct,
                    changeUsd = changeUsd,
                    usdOf = { null },
                    showTotal = true,
                    showBalances = true,
                    refreshing = false,
                    updatedAtMs = null,
                    notice = notice,
                    unreachable = unreachable,
                    onAccount = {},
                    onRefresh = {},
                )
            }
        }
    }

    @Test
    fun theChangeOfTheTotalShowsUnderIt() {
        render(changePct = -1.15, changeUsd = -1_728.13)

        compose.onNodeWithText("▼ 1.15% · −\$1,728.13 Today").assertIsDisplayed()
    }

    @Test
    fun thePageNoticeShowsWhileAChainThatDidNotAnswerNamesItselfUnderItsIsland() {
        render(notice = "Some balances could not be refreshed.", unreachable = setOf(ethereum))

        compose.onNodeWithText("Some balances could not be refreshed.").assertIsDisplayed()
        compose.onNodeWithText("Couldn't reach Ethereum. Lumio will retry.").assertIsDisplayed()
    }
}
