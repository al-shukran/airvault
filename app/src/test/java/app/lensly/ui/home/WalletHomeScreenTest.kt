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
import app.lensly.app.WalletReviewRecord
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.theme.LenslyTheme
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's own behaviour: the empty state with its two ways to start, no Add wallet once a wallet exists, at most three recent
 * records with the newest first, and the readiness chips that open their settings screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-mdpi")
class WalletHomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun pauseTheClock() {
        // The hero turns forever, so the clock is paused and advanced by [settle].
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun withNoWalletsHomeShowsCreateAndRestoreAndNoCarousel() {
        compose.setContent {
            LenslyTheme {
                home(wallets = emptyList())
            }
        }
        settle()

        compose.onNodeWithText("Create wallet", ignoreCase = true).assertExists()
        compose.onNodeWithText("Restore wallet", ignoreCase = true).assertExists()
        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(0)
        compose.onNodeWithTag(ADD_WALLET_TAG).assertDoesNotExist()
    }

    @Test
    fun withAWalletsHomeHasNoAddWalletNode() {
        compose.setContent {
            LenslyTheme {
                home(wallets = wallets(2))
            }
        }
        settle()

        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(2)
        compose.onNodeWithText("Add wallet").assertDoesNotExist()
        compose.onNodeWithTag(ADD_WALLET_TAG).assertDoesNotExist()
    }

    @Test
    fun recentActivityShowsTheThreeNewestRecords() {
        val now = 1_700_000_000_000L
        val records = listOf(
            review(walletId = "w1", atMs = now - 4 * MINUTE, amount = "0.04 MON"),
            review(walletId = "w1", atMs = now - 1 * MINUTE, amount = "0.01 MON"),
            review(walletId = "w2", atMs = now - 3 * MINUTE, amount = "0.03 MON"),
            review(walletId = "w2", atMs = now - 2 * MINUTE, amount = "0.02 MON"),
        )
        compose.setContent {
            LenslyTheme {
                home(wallets = wallets(2), records = records, nowMs = now)
            }
        }
        settle()

        compose.onAllNodesWithTag(HOME_ACTIVITY_ROW_TAG).assertCountEquals(3)
        compose.onNodeWithText("Sent 0.01 MON on Monad Testnet").assertExists()
        compose.onNodeWithText("Sent 0.02 MON on Monad Testnet").assertExists()
        compose.onNodeWithText("Sent 0.03 MON on Monad Testnet").assertExists()
        compose.onNodeWithText("Sent 0.04 MON on Monad Testnet").assertDoesNotExist()
    }

    @Test
    fun readinessChipsOpenTheirSettingsScreens() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        home(
                            wallets = wallets(1),
                            readiness = HomeReadiness(networkCount = 3, tokenCount = 5, lockout = "Default"),
                            onNetworks = { nav.navigate(HiddenRoute.NETWORKS) },
                            onTokens = { nav.navigate(HiddenRoute.TOKENS) },
                            onCatalog = { nav.navigate(HiddenRoute.CATALOG) },
                            onLockout = { nav.navigate(HiddenRoute.FAILURE_CAP) },
                        )
                    }
                    composable(HiddenRoute.NETWORKS) {}
                    composable(HiddenRoute.TOKENS) {}
                    composable(HiddenRoute.CATALOG) {}
                    composable(HiddenRoute.FAILURE_CAP) {}
                }
            }
        }
        settle()

        compose.onNodeWithText("3 networks").performClick()
        settle()
        assertEquals(HiddenRoute.NETWORKS, nav.currentBackStackEntry?.destination?.route)
        nav.popBackStack()
        settle()

        compose.onNodeWithText("5 tokens").performClick()
        settle()
        assertEquals(HiddenRoute.TOKENS, nav.currentBackStackEntry?.destination?.route)
        nav.popBackStack()
        settle()

        compose.onNodeWithText("Catalog").performClick()
        settle()
        assertEquals(HiddenRoute.CATALOG, nav.currentBackStackEntry?.destination?.route)
        nav.popBackStack()
        settle()

        compose.onNodeWithText("Lockout Default").performClick()
        settle()
        assertEquals(HiddenRoute.FAILURE_CAP, nav.currentBackStackEntry?.destination?.route)
    }

    @Composable
    private fun home(
        wallets: List<WalletMeta>,
        records: List<WalletReviewRecord>? = emptyList(),
        readiness: HomeReadiness = HomeReadiness(),
        nowMs: Long = 1_700_000_000_000L,
        onNetworks: () -> Unit = {},
        onTokens: () -> Unit = {},
        onCatalog: () -> Unit = {},
        onLockout: () -> Unit = {},
    ) {
        WalletHomeScreen(
            wallets = wallets,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onAdd = {},
            onWallets = {},
            onSettings = {},
            onLock = {},
            onStartPairing = {},
            onScan = {},
            records = records,
            readiness = readiness,
            onNetworks = onNetworks,
            onTokens = onTokens,
            onCatalog = onCatalog,
            onLockout = onLockout,
            nowMs = nowMs,
        )
    }

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

    private fun review(walletId: String, atMs: Long, amount: String): WalletReviewRecord = WalletReviewRecord(
        walletId = walletId,
        record = ReviewRecord(
            atMs = atMs,
            outcome = ReviewOutcome.SIGNED,
            kind = ReviewKind.TRANSACTION,
            chainId = 10_143L,
            networkName = "Monad Testnet",
            accountIndex = 0,
            address = null,
            function = "transfer",
            to = null,
            amount = amount,
            tokenContract = null,
            fee = emptyList(),
            params = emptyList(),
            origin = null,
            refusal = null,
            dataHash = "00",
        ),
    )

    private companion object {
        const val SETTLE_MS = 1_000L
        const val MINUTE = 60_000L
    }
}
