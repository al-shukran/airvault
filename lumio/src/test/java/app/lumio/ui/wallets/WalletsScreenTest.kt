package app.lumio.ui.wallets

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The Accounts tab (C1): the active wallet tagged ACTIVE, and each wallet's change over 24 hours under its total. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theActiveWalletIsTaggedAndEachWalletShowsItsChange() {
        compose.setContent {
            ClearAirTheme {
                WalletsScreen(
                    rows = listOf(
                        WalletRow("a", "Air 1", "30 accounts · 4 apps", usd = 149_769.10, changePct = -1.15, active = true),
                        WalletRow("b", "Air 2", "3 accounts · 2 apps", usd = 2_140.55, changePct = 0.62),
                    ),
                    totalUsd = 151_909.65,
                    showTotal = true,
                    refreshing = false,
                    updatedAtMs = null,
                    notice = null,
                    onWallet = {},
                    onPair = {},
                    onRefresh = {},
                )
            }
        }

        compose.onAllNodesWithText("ACTIVE").assertCountEquals(1)
        compose.onNodeWithText("▼ 1.15%").assertIsDisplayed()
        compose.onNodeWithText("▲ 0.62%").assertIsDisplayed()
    }
}
