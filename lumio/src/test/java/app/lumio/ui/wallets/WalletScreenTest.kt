package app.lumio.ui.wallets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletReads
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The wallet page (C2): the change under its total, the page notice, and the note under a chain that did not answer, with its Retry. */
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
        reads: WalletReads = WalletReads(),
        onRetry: (String) -> Unit = {},
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
                    updatedAtMs = null,
                    notice = notice,
                    reads = reads,
                    onRetry = onRetry,
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
    fun thePageNoticeShowsForARead() {
        render(notice = "Some balances could not be refreshed.")

        compose.onNodeWithText("Some balances could not be refreshed.").assertIsDisplayed()
    }

    @Test
    fun aChainThatDidNotAnswerNamesItselfUnderItsIslandWithARetry() {
        render(reads = WalletReads(unreachable = setOf(ethereum)))

        compose.onNodeWithText("Couldn't reach Ethereum. Showing the last known balance.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun retryReadsThatChainFamilyAgain() {
        var retried: String? = null
        render(reads = WalletReads(unreachable = setOf(ethereum)), onRetry = { retried = it })

        compose.onNodeWithText("Retry").performClick()

        assertEquals(Networks.EIP155, retried)
    }
}
