package app.lumio.ui.wallets

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import app.lumio.data.Clock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lensly.ui.theme.ClearAirSpacing
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

    @Test
    fun aNoticeShowsBesideTheUpdatedLineAndNeverReplacesIt() {
        compose.setContent {
            ClearAirTheme {
                WalletsScreen(
                    rows = listOf(WalletRow("a", "Air 1", "30 accounts · 4 apps", usd = null)),
                    totalUsd = null,
                    showTotal = true,
                    refreshing = false,
                    updatedAtMs = NOW_MS - 3 * MINUTE_MS,
                    notice = REFRESH_NOTICE,
                    onWallet = {},
                    onPair = {},
                    onRefresh = {},
                    clock = Clock { NOW_MS },
                )
            }
        }

        compose.onNodeWithText(REFRESH_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("Updated 3 min ago").assertIsDisplayed()
    }

    @Test
    fun theEmptyAccountsStateKeepsTheSideGutter() {
        compose.setContent {
            ClearAirTheme {
                WalletsScreen(
                    rows = emptyList(),
                    totalUsd = null,
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

        compose.onNodeWithText("Pair with Lensly").assertLeftPositionInRootIsEqualTo(ClearAirSpacing.gutter)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val MINUTE_MS = 60_000L
        const val REFRESH_NOTICE = "Some balances could not be refreshed. The last known balances are shown."
    }
}
