package app.lensly.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.app.WalletApp
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wallet screen. It lists the Apps the wallet has installed with their account counts, and a tap on an App opens it. Manage Apps opens
 * the Apps screen. The wallet's options open from the overflow icon or a long press on the header. They hold the wallet's own actions, and
 * showing the secret asks for a warning first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class WalletDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the route keeps while the wallet screen is open: the App last opened, and the secret or history that was asked for. */
    private class Route {
        var opened: WalletApp? = null
        var managed = false
        var revealed = false
        var historyOpened = false
    }

    /** The wallet screen of one wallet with [accounts] Ethereum accounts and [bitcoin] Bitcoin accounts. */
    @Composable
    private fun Detail(route: Route, accounts: Int, bitcoin: Int, kind: WalletKind = WalletKind.MNEMONIC) {
        WalletDetailScreen(
            wallet = WalletMeta(
                id = "w1",
                name = "Seed wallet",
                createdAtMs = 0L,
                deviceGenerated = true,
                accountCount = accounts,
                masterFingerprintHex = "1a2b3c4d",
                accountKeys = emptyList(),
                kind = kind,
                autoNameBase = "Seed wallet",
            ),
            walletProfile = WalletProfile(),
            walletPosition = 0,
            accounts = mapOf(
                WalletApp.BITCOIN to bitcoin,
                WalletApp.ETHEREUM to accounts,
                WalletApp.SOLANA to 0,
                WalletApp.ALGORAND to 0,
            ),
            onBack = {},
            onOpenApp = { app -> route.opened = app },
            onManageApps = { route.managed = true },
            onShowSecret = { route.revealed = true },
            onHistory = { route.historyOpened = true },
            onEditWallet = {},
            onDeleteWallet = {},
        )
    }

    @Test
    fun theWalletListsItsTwoAppsWithTheirAccountCounts() {
        compose.setContent {
            LumenTheme {
                Detail(Route(), accounts = 3, bitcoin = 2)
            }
        }

        compose.onNodeWithText("Bitcoin").assertIsDisplayed()
        compose.onNodeWithText("2 accounts").assertIsDisplayed()
        compose.onNodeWithText("Ethereum").assertIsDisplayed()
        compose.onNodeWithText("3 accounts").assertIsDisplayed()
    }

    @Test
    fun aWalletShowsOnlyItsInstalledAppsAndOpensTheAppsScreen() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Detail(route, accounts = 3, bitcoin = 0)
            }
        }

        compose.onNodeWithText("Bitcoin").assertDoesNotExist()
        compose.onNodeWithText("Ethereum").assertIsDisplayed()
        compose.onNodeWithText("Manage Apps").performClick()
        assertTrue(route.managed)
    }

    @Test
    fun aWalletWithNoInstalledAppSaysSoAndOpensTheAppsScreen() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Detail(route, accounts = 0, bitcoin = 0)
            }
        }

        compose.onNodeWithText("No Apps Installed").assertIsDisplayed()
        compose.onNodeWithText("Manage Apps").performClick()
        assertTrue(route.managed)
    }

    @Test
    fun aSingleKeyWalletHasNoManageAppsRow() {
        compose.setContent {
            LumenTheme {
                Detail(Route(), accounts = 1, bitcoin = 0, kind = WalletKind.SINGLE_KEY)
            }
        }

        compose.onNodeWithText("Manage Apps").assertDoesNotExist()
    }

    @Test
    fun tappingAnAppOpensIt() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Detail(route, accounts = 3, bitcoin = 2)
            }
        }

        compose.onNodeWithText("Bitcoin").performClick()
        assertEquals(WalletApp.BITCOIN, route.opened)
        compose.onNodeWithText("Ethereum").performClick()
        assertEquals(WalletApp.ETHEREUM, route.opened)
    }

    @Test
    fun theWalletOptionsHoldTheWalletActionsAndNoAccountActions() {
        compose.setContent {
            LumenTheme {
                Detail(Route(), accounts = 3, bitcoin = 2)
            }
        }

        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Edit Wallet Profile").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Show Secret Phrase").assertIsDisplayed()
        compose.onNodeWithText("Delete Wallet").assertIsDisplayed()
        // Generating, selecting, pairing and signing are on each App, so the wallet's options do not hold them.
        compose.onNodeWithText("Generate Accounts").assertDoesNotExist()
        compose.onNodeWithText("Select Accounts").assertDoesNotExist()
    }

    @Test
    fun theHistoryOptionOpensTheHistoryOfTheWallet() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Detail(route, accounts = 1, bitcoin = 0)
            }
        }

        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()
        assertTrue(route.historyOpened)
    }

    @Test
    fun aLongPressOnTheWalletHeaderOpensTheWalletOptions() {
        compose.setContent {
            LumenTheme {
                Detail(Route(), accounts = 3, bitcoin = 2)
            }
        }

        compose.onNodeWithText("Seed wallet").performTouchInput { longClick() }
        compose.onNodeWithText("Edit Wallet Profile").assertIsDisplayed()
    }

    @Test
    fun showingTheSecretAsksForAWarningBeforeItOpens() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Detail(route, accounts = 3, bitcoin = 2)
            }
        }

        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Show Secret Phrase").performClick()
        compose.onNodeWithText("This reveals your secret recovery phrase", substring = true).assertIsDisplayed()
        assertFalse(route.revealed)

        // Cancel goes back to the options without opening the secret.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Show Secret Phrase").assertIsDisplayed()
        assertFalse(route.revealed)

        compose.onNodeWithText("Show Secret Phrase").performClick()
        compose.onNodeWithText("Reveal").performClick()
        assertTrue(route.revealed)
    }

    @Test
    fun aSingleKeyWalletShowsItsOneAccountAndItsPrivateKey() {
        compose.setContent {
            LumenTheme {
                Detail(Route(), accounts = 1, bitcoin = 0, kind = WalletKind.SINGLE_KEY)
            }
        }

        compose.onNodeWithText("1 account").assertIsDisplayed()
        // Only the Ethereum account is installed, so the Bitcoin, Solana and Algorand rows are not listed.
        assertTrue(compose.onAllNodesWithText("0 accounts").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Show Private Key").assertIsDisplayed()
    }
}
