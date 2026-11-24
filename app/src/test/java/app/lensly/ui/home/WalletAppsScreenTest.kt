package app.lensly.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.WalletApp
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Apps screen. Each App is a row with one text action: Install for an App the wallet lacks, which installs it at once, and Uninstall for an
 * installed App, which asks first and says what is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class WalletAppsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the route keeps while the screen is open: the App last installed or uninstalled. */
    private class Route {
        var installed: WalletApp? = null
        var uninstalled: WalletApp? = null
    }

    @Composable
    private fun Apps(route: Route, accounts: Map<WalletApp, Int>) {
        WalletAppsScreen(
            accounts = accounts,
            onBack = {},
            onInstall = { route.installed = it },
            onUninstall = { route.uninstalled = it },
        )
    }

    @Test
    fun theAppsAreListedWithTheirStateAndOnlyInstallOrUninstallActions() {
        compose.setContent {
            LumenTheme {
                Apps(Route(), accounts = mapOf(WalletApp.BITCOIN to 1))
            }
        }

        compose.onNodeWithText("Apps").assertIsDisplayed()
        compose.onNodeWithText("Installed · 1 account").assertIsDisplayed()
        compose.onAllNodesWithText("Not installed").assertCountEquals(3)
        compose.onAllNodesWithText("Install").assertCountEquals(3)
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        compose.onNodeWithText("Enable", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Disable", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun installingAnAppTakesOneTap() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Apps(route, accounts = mapOf(WalletApp.BITCOIN to 1))
            }
        }

        compose.onAllNodesWithText("Install").onFirst().performClick()

        assertEquals(WalletApp.ETHEREUM, route.installed)
        assertNull(route.uninstalled)
    }

    @Test
    fun uninstallingAsksFirstAndSaysWhatIsLost() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Apps(route, accounts = mapOf(WalletApp.BITCOIN to 2))
            }
        }

        compose.onNodeWithText("Uninstall").performClick()

        compose.onNodeWithText("Uninstall Bitcoin?").assertIsDisplayed()
        compose.onNodeWithText("This removes 2 accounts from this wallet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Their names, labels and groups are lost.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Lumio keeps its copy of these accounts until you pair again.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(route.uninstalled)
    }

    @Test
    fun confirmingTheUninstallRemovesTheApp() {
        val route = Route()
        compose.setContent {
            LumenTheme {
                Apps(route, accounts = mapOf(WalletApp.BITCOIN to 2))
            }
        }

        compose.onNodeWithText("Uninstall").performClick()
        compose.onAllNodesWithText("Uninstall").onLast().performClick()

        assertEquals(WalletApp.BITCOIN, route.uninstalled)
        assertNull(route.installed)
    }
}
