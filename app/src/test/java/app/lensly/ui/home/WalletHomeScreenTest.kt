package app.lensly.ui.home

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.theme.LenslyTheme
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Home, from the top down, on a phone's 411 x 914 dp window. The wallets come as a carousel of cards, the recent activity as three rows at
 * most, and the readiness as four tiles. With no wallet the sections give way to the empty state. The dock never covers the content once
 * it has scrolled to the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-port-mdpi")
class WalletHomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreAnimators() {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun zeroWalletsShowsTheEmptyStateWithCreateAndRestoreAndNoCarousel() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = emptyList(), recent = emptyList())
            }
        }
        settle()

        compose.onNodeWithText("your first wallet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("CREATE").assertIsDisplayed()
        compose.onNodeWithText("RESTORE").assertIsDisplayed()
        compose.onNodeWithTag(HOME_CAROUSEL_TAG).assertDoesNotExist()
        compose.onAllNodesWithTag(WALLET_CARD_TAG).assertCountEquals(0)
        // Scanning needs a wallet, so the empty state has no Scan to sign button.
        compose.onNodeWithTag(HOME_SCAN_TAG).assertDoesNotExist()
        // The chart and the backup panel need a wallet, so the empty state has neither.
        compose.onNodeWithTag(HOME_CHART_TAG).assertDoesNotExist()
        compose.onNodeWithTag(HOME_BACKUP_TAG).assertDoesNotExist()
    }

    @Test
    fun zeroWalletsCreateStartsTheCeremony() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(
                            wallets = emptyList(),
                            recent = emptyList(),
                            onCreate = { nav.navigate(HiddenRoute.CEREMONY_CHOICE) },
                        )
                    }
                    composable(HiddenRoute.CEREMONY_CHOICE) {}
                }
            }
        }
        settle()

        compose.onNodeWithText("CREATE").performClick()
        settle()

        assertEquals(HiddenRoute.CEREMONY_CHOICE, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun zeroWalletsRestoreOpensTheRestoreFlow() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(
                            wallets = emptyList(),
                            recent = emptyList(),
                            onRestore = { nav.navigate(HiddenRoute.RESTORE) },
                        )
                    }
                    composable(HiddenRoute.RESTORE) {}
                }
            }
        }
        settle()

        compose.onNodeWithText("RESTORE").performClick()
        settle()

        assertEquals(HiddenRoute.RESTORE, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun fiveWalletsShowEveryWalletInTheCarouselWithNoAddTile() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(5), recent = emptyList())
            }
        }
        settle()

        compose.onNodeWithTag(HOME_CAROUSEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_WALLET_TAG).assertDoesNotExist()
        // The row holds five items, and the cards on screen are only some of them, so the row scrolls to reach each card.
        assertEquals(5, compose.onNodeWithTag(HOME_CAROUSEL_TAG).fetchSemanticsNode().carouselItemCount())
        val cards = mutableSetOf<String>()
        for (index in 0 until 5) {
            compose.onNodeWithTag(HOME_CAROUSEL_TAG).performScrollToIndex(index)
            compose.waitForIdle()
            compose.onAllNodesWithTag(WALLET_CARD_TAG).fetchSemanticsNodes().forEach { cards += cardText(it) }
        }
        assertEquals(5, cards.size, "every wallet has one card in the carousel: $cards")
    }

    @Test
    fun recentActivityShowsTheThreeNewestNewestFirst() {
        compose.mainClock.autoAdvance = false
        // Given out of order, so the screen has to sort them.
        val records = listOf(4, 0, 2, 1, 3).map { hoursAgo -> activity(hoursAgo) }
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), recent = records)
            }
        }
        settle()

        compose.onAllNodesWithTag(HOME_ACTIVITY_ROW_TAG).assertCountEquals(3)
        // Each row reads "Signed · Send N", so the match is by part of the text.
        compose.onNodeWithText("Send 4", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Send 3", substring = true).assertDoesNotExist()
        val tops = listOf("Send 0", "Send 1", "Send 2").map { compose.onNodeWithText(it, substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.zipWithNext().all { (above, below) -> above < below }, "newest first, top to bottom: $tops")
    }

    @Test
    fun recentActivitySeeAllOpensTheActivityRoute() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(
                            wallets = wallets(2),
                            recent = listOf(activity(0)),
                            onActivity = { nav.navigate(HiddenRoute.activity()) },
                        )
                    }
                    composable(HiddenRoute.ACTIVITY) {}
                }
            }
        }
        settle()

        compose.onNodeWithText("SEE ALL").performClick()
        settle()

        assertEquals(HiddenRoute.ACTIVITY, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun recentActivityWithNoRecordSaysNothingSignedYet() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), recent = emptyList())
            }
        }
        settle()

        compose.onNodeWithText("Nothing signed yet").assertIsDisplayed()
        compose.onAllNodesWithTag(HOME_ACTIVITY_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun readinessTilesOpenTheirSettingsScreens() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(
                            wallets = wallets(2),
                            recent = emptyList(),
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

        val tiles = listOf(
            READINESS_NETWORKS_TAG to HiddenRoute.NETWORKS,
            READINESS_TOKENS_TAG to HiddenRoute.TOKENS,
            READINESS_CATALOG_TAG to HiddenRoute.CATALOG,
            READINESS_LOCKOUT_TAG to HiddenRoute.FAILURE_CAP,
        )
        for ((tag, route) in tiles) {
            // The tiles sit below the fold, so the content scrolls to its end, where every tile is above the dock.
            scrollHomeToEnd()
            compose.onNodeWithTag(tag).performClick()
            settle()
            assertEquals(route, nav.currentBackStackEntry?.destination?.route, "tile $tag")
            compose.runOnUiThread { nav.popBackStack() }
            settle()
        }
    }

    @Test
    fun catalogTileShowsTheInstalledSequence() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), readiness = READINESS.copy(catalogSequence = 3))
            }
        }
        settle()

        compose.onNodeWithTag(READINESS_CATALOG_TAG).assertTextContains("Sequence 3")
    }

    @Test
    fun catalogTileReadsBuiltInWhenNoPackageIsInstalled() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), readiness = READINESS.copy(catalogSequence = null))
            }
        }
        settle()

        compose.onNodeWithTag(READINESS_CATALOG_TAG).assertTextContains("Built-in")
    }

    @Test
    fun scanToSignOpensTheScanRouteAndTheDockHasNoScan() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(wallets = wallets(2), recent = emptyList(), onScan = { nav.navigate(HiddenRoute.SCAN_ANY) })
                    }
                    composable(HiddenRoute.SCAN_ANY) {}
                }
            }
        }
        settle()

        compose.onNodeWithContentDescription("Scan a QR code").assertDoesNotExist()
        compose.onNodeWithTag(HOME_SCAN_TAG).performClick()
        settle()

        assertEquals(HiddenRoute.SCAN_ANY, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun tappingTheChartOpensTheActivityScreen() {
        compose.mainClock.autoAdvance = false
        var opened = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), recent = listOf(activity(0)), onActivity = { opened = true })
            }
        }
        settle()

        compose.onNodeWithTag(HOME_CHART_TAG).performClick()

        assertTrue(opened)
    }

    @Test
    fun theContentEndsAboveTheDock() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), recent = listOf(activity(0)), readiness = READINESS)
            }
        }
        settle()
        scrollHomeToEnd()

        val dock = compose.onNodeWithTag(HOME_DOCK_TAG).fetchSemanticsNode().boundsInRoot
        val lastTile = compose.onNodeWithTag(READINESS_LOCKOUT_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(lastTile.bottom <= dock.top, "the last tile ${lastTile.bottom} must clear the dock at ${dock.top}")
    }

    @Test
    fun theDockSettingsOpensSettingsAndTheTopBarHasNoSettingsIcon() {
        lateinit var nav: NavHostController
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            LenslyTheme {
                NavHost(navController = nav, startDestination = HiddenRoute.HOME) {
                    composable(HiddenRoute.HOME) {
                        Home(wallets = wallets(2), recent = emptyList(), onSettings = { nav.navigate(HiddenRoute.SETTINGS) })
                    }
                    composable(HiddenRoute.SETTINGS) {}
                }
            }
        }
        settle()

        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lock the vault").assertExists()
        compose.onNodeWithTag(HOME_DOCK_SETTINGS_TAG).performClick()
        settle()

        assertEquals(HiddenRoute.SETTINGS, nav.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun theDockHoldsFourLabelledActionsEvenlySpaced() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LenslyTheme {
                Home(wallets = wallets(2), recent = emptyList())
            }
        }
        settle()

        val actions = listOf(
            HOME_DOCK_PAIR_TAG to "PAIR",
            HOME_DOCK_ACTIVITY_TAG to "ACTIVITY",
            HOME_DOCK_WALLETS_TAG to "WALLETS",
            HOME_DOCK_SETTINGS_TAG to "SETTINGS",
        )
        actions.forEach { (tag, label) -> compose.onNodeWithTag(tag).assertTextEquals(label) }
        val centres = actions.map { (tag, _) -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x }
        val steps = centres.zipWithNext { left, right -> right - left }
        assertTrue(steps.all { abs(it - steps.first()) < 1f }, "the four actions are evenly spaced: $centres")
    }

    /** Home with its callbacks, where the ones a test does not look at do nothing. The readiness is the usual one unless a test says. */
    @Composable
    private fun Home(
        wallets: List<WalletMeta>?,
        recent: List<WalletReviewRecord>? = null,
        readiness: HomeReadiness? = READINESS,
        onCreate: () -> Unit = {},
        onRestore: () -> Unit = {},
        onScan: () -> Unit = {},
        onActivity: () -> Unit = {},
        onSettings: () -> Unit = {},
        onNetworks: () -> Unit = {},
        onTokens: () -> Unit = {},
        onCatalog: () -> Unit = {},
        onLockout: () -> Unit = {},
    ) {
        WalletHomeScreen(
            wallets = wallets,
            recent = recent,
            readiness = readiness,
            nowMs = NOW_MS,
            onOpen = {},
            onEdit = {},
            onPair = {},
            onCreate = onCreate,
            onRestore = onRestore,
            onScan = onScan,
            onActivity = onActivity,
            onWallets = {},
            onSettings = onSettings,
            onLock = {},
            onStartPairing = {},
            onNetworks = onNetworks,
            onTokens = onTokens,
            onCatalog = onCatalog,
            onLockout = onLockout,
            onBackup = {},
        )
    }

    /** Lets the paused clock finish the screen's own timings, so the screen is in its settled state. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    /** Swipes the Home content up until it stops moving, so its last section sits above the dock. */
    private fun scrollHomeToEnd() {
        repeat(SWIPES) {
            compose.onNodeWithTag(HOME_SCROLL_TAG).performTouchInput {
                swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 200)
            }
            compose.mainClock.advanceTimeBy(FLING_MS)
            compose.waitForIdle()
        }
    }

    /** The count of items in a lazy row, as the row reports it to accessibility services. */
    private fun SemanticsNode.carouselItemCount(): Int = config[SemanticsProperties.CollectionInfo].columnCount

    /** What a wallet card says, as one string, so two cards of different wallets never compare equal. */
    private fun cardText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }.orEmpty()

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

    /** A signed transaction of wallet w1, made [hoursAgo] hours before [NOW_MS]. Its row reads "Signed · Send" and the hours. */
    private fun activity(hoursAgo: Int): WalletReviewRecord = WalletReviewRecord(
        walletId = "w1",
        record = ReviewRecord(
            atMs = NOW_MS - hoursAgo * HOUR_MS,
            outcome = ReviewOutcome.SIGNED,
            kind = ReviewKind.TRANSACTION,
            chainId = 1L,
            networkName = "Ethereum",
            accountIndex = 0,
            address = "0x00000000000000000000000000000000000000AA",
            function = "Send $hoursAgo",
            to = null,
            amount = null,
            tokenContract = null,
            fee = emptyList(),
            params = emptyList(),
            origin = null,
            refusal = null,
            dataHash = "00",
        ),
    )

    private companion object {
        /** The moment the relative times are read against, so the times on screen do not depend on when the test runs. */
        const val NOW_MS = 1_800_000_000_000L
        const val HOUR_MS = 3_600_000L
        const val SETTLE_MS = 1_000L
        const val FLING_MS = 1_000L
        const val SWIPES = 10

        val READINESS = HomeReadiness(
            networkCount = 12,
            tokenCount = 48,
            catalogSequence = null,
            lockout = FailureCapChoice.DEFAULT,
        )
    }
}
