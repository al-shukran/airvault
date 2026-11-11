package app.lensly.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home and the Wallets list: one carousel card per wallet, no add tile on Home, no catalog action, the All wallets link to the
 * full list, and the one scan action. The navigation tests run the same route names as the app through a real NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class WalletDashboardTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun pauseTheClock() {
        // The hero turns forever, so the clock is paused and advanced by [settle].
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun homeShowsOneCarouselCardPerWalletAndNoAddTile() {
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2))
            }
        }
        settle()

        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(2)
        compose.onNodeWithTag(ADD_WALLET_TAG).assertDoesNotExist()
        compose.onNodeWithText("Update catalog", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun allWalletsOpensTheListOfEveryWallet() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(wallets = wallets(5), onWallets = { nav.navigate(HiddenRoute.WALLETS) })
                    }
                    composable(HiddenRoute.WALLETS) {
                        WalletsScreen(
                            wallets = wallets(5),
                            onOpen = {},
                            onEdit = {},
                            onPair = {},
                            onAdd = {},
                            onBack = {},
                        )
                    }
                }
            }
        }
        settle()
        assertTrue(compose.onAllNodesWithTag(WALLET_CARD_TAG).fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithText("All wallets →").performClick()
        settle()

        assertEquals(HiddenRoute.WALLETS, nav.currentBackStackEntry?.destination?.route)
        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(5)
        compose.onNodeWithTag(ADD_WALLET_TAG).assertExists()
    }

    @Test
    fun scanToSignNavigatesToTheScanRoute() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(wallets = wallets(2), onScan = { nav.navigate(HiddenRoute.SCAN_ANY) })
                    }
                    composable(HiddenRoute.SCAN_ANY) {}
                }
            }
        }
        settle()

        compose.onNodeWithText("Scan to sign", ignoreCase = true).performClick()
        settle()

        assertEquals("scan", nav.currentBackStackEntry?.destination?.route)
    }

    /** Home with its callbacks, where the ones a test does not look at do nothing. */
    @Composable
    private fun Home(
        wallets: List<WalletMeta>,
        onScan: () -> Unit = {},
        onWallets: () -> Unit = {},
    ) {
        WalletHomeScreen(
            wallets = wallets,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onAdd = {},
            onWallets = onWallets,
            onSettings = {},
            onLock = {},
            onStartPairing = {},
            onScan = onScan,
        )
    }

    /** Lets the hero's turn and the navigation transitions finish, so the screen is in its settled state. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun wallets(count: Int): List<WalletMeta> = (1..count).map { index ->
        WalletMeta(
            id = "w$index",
            name = "Wallet $index",
            createdAtMs = 0L,
            deviceGenerated = true,
            accountCount = 1,
            masterFingerprintHex = "%08x".format(index),
            accountXpub = null,
            kind = WalletKind.MNEMONIC,
            autoNameBase = "Wallet $index",
        )
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
