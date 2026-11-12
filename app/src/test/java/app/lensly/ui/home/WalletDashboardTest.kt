package app.lensly.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
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
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home and the Wallets list: one card per wallet in the Home carousel, no add tile once there is a wallet, no catalog action, All
 * wallets to the full list, and the Scan to sign button to the scan. The navigation tests run the same route names as the app through a
 * real NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class WalletDashboardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeShowsOneCardPerWalletWithNoAddTileAndNoCatalogAction() {
        compose.mainClock.autoAdvance = false
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
    fun homeCarouselHoldsEveryWalletAndAllWalletsOpensTheListOfEveryWallet() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
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
        // The carousel holds all five wallets. Its cards on screen are fewer, so the row is checked by its item count.
        assertEquals(5, compose.onNodeWithTag(HOME_CAROUSEL_TAG).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].columnCount)

        compose.onNodeWithText("ALL WALLETS").performClick()
        settle()

        assertEquals(HiddenRoute.WALLETS, nav.currentBackStackEntry?.destination?.route)
        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(5)
        compose.onNodeWithTag(ADD_WALLET_TAG).assertExists()
    }

    @Test
    fun theScanToSignButtonNavigatesToTheScanRoute() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
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

        compose.onNodeWithTag(HOME_SCAN_TAG).performClick()
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
            recent = emptyList(),
            readiness = HomeReadiness(
                networkCount = 12,
                tokenCount = 48,
                catalogSequence = null,
                lockout = FailureCapChoice.DEFAULT,
            ),
            nowMs = NOW_MS,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onCreate = {},
            onRestore = {},
            onScan = onScan,
            onActivity = {},
            onWallets = onWallets,
            onSettings = {},
            onLock = {},
            onStartPairing = {},
            onNetworks = {},
            onTokens = {},
            onCatalog = {},
            onLockout = {},
            onBackup = {},
        )
    }

    /** Lets the entrance and the navigation transitions finish, so the screen is in its settled state. */
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
        const val NOW_MS = 1_800_000_000_000L
    }
}
