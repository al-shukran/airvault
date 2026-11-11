package app.lensly.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.lensly.app.AccountProfile
import app.lensly.app.ProfileColour
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LenslyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wallet detail screen. Adding an account keeps the scroll position and appends its row. Each row shows its full address.
 * A long press, or a tap, on an account opens its options. The wallet's options open from the overflow icon or a long press on
 * the header, and showing the secret asks for a warning first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class WalletDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the route keeps for the wallet while the screen is open: the account count, and the addresses derived so far. */
    private class Route(accounts: Int) {
        var count by mutableIntStateOf(accounts)
        var addresses by mutableStateOf(List(accounts, ::addressAt))
        var exported = -1
        var edited = -1
        var revealed = false

        /** Derives the address of the account the list does not have yet, as the route does after the count changes. */
        fun deriveNext() {
            addresses = addresses + addressAt(addresses.size)
        }
    }

    @Composable
    private fun Detail(route: Route, listState: LazyListState, kind: WalletKind = WalletKind.MNEMONIC) {
        WalletDetailScreen(
            wallet = WalletMeta(
                id = "w1",
                name = "Seed wallet",
                createdAtMs = 0L,
                deviceGenerated = true,
                accountCount = route.count,
                masterFingerprintHex = "1a2b3c4d",
                accountXpub = null,
                kind = kind,
                autoNameBase = "Seed wallet",
            ),
            addresses = route.addresses,
            accountProfiles = profiles,
            walletProfile = WalletProfile(),
            walletPosition = 0,
            onBack = {},
            onLock = {},
            onAddAccount = { route.count += 1 },
            onBulkGenerate = {},
            onShowSecret = { route.revealed = true },
            onEditWallet = {},
            onEditAccount = { route.edited = it },
            onExportAccount = { route.exported = it },
            onRenameAccounts = {},
            onStyleAccounts = {},
            onResetAccountNames = {},
            onDeleteWallet = {},
            listState = listState,
        )
    }

    @Test
    fun addingAnAccountKeepsTheScrollPositionAndAppendsTheRow() {
        // Twelve accounts, with the list scrolled into the middle, so the "Add account" row is still on screen.
        val route = Route(accounts = 12)
        lateinit var listState: LazyListState
        compose.setContent {
            LenslyTheme {
                listState = rememberLazyListState()
                LaunchedEffect(Unit) { listState.scrollToItem(4, 30) }
                Detail(route, listState)
            }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        assertTrue("the list starts scrolled, so the test can see it move", before.first > 0)

        compose.onNodeWithText("ADD ACCOUNT").performClick()
        compose.waitForIdle()
        // The new account is counted, and its row waits for its address. The rows already shown stay as they were.
        compose.onAllNodesWithText("DERIVING ADDRESS").assertCountEquals(1)
        compose.onAllNodesWithText(groupedAddress(addressAt(12))).assertCountEquals(0)
        assertEquals(13, route.count)
        assertEquals(before, compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })

        compose.runOnIdle { route.deriveNext() }
        compose.waitForIdle()
        compose.onAllNodesWithText(groupedAddress(addressAt(12))).assertCountEquals(1)
        compose.onAllNodesWithText("DERIVING ADDRESS").assertCountEquals(0)
        assertEquals(before, compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset })
    }

    @Test
    fun everyAccountRowShowsItsFullAddress() {
        val route = Route(accounts = 3)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState())
            }
        }

        route.addresses.forEach { address ->
            compose.onNodeWithText(groupedAddress(address)).assertExists()
        }
        compose.onAllNodesWithText("…", substring = true).assertCountEquals(0)
    }

    @Test
    fun aLongPressOnAnAccountOpensItsExportAndEditOptions() {
        val route = Route(accounts = 3)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState())
            }
        }

        compose.onNodeWithText("MAIN").performTouchInput { longClick() }
        compose.onNodeWithText("Export address").assertIsDisplayed()
        compose.onNodeWithText("Edit profile").assertIsDisplayed()

        compose.onNodeWithText("Export address").performClick()
        assertEquals(0, route.exported)
    }

    @Test
    fun theOverflowIconOpensTheWalletOptions() {
        val route = Route(accounts = 3)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState())
            }
        }

        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Edit wallet profile").assertIsDisplayed()
        compose.onNodeWithText("Generate accounts").assertIsDisplayed()
        compose.onNodeWithText("Select accounts").assertIsDisplayed()
        compose.onNodeWithText("Delete wallet").assertIsDisplayed()
    }

    @Test
    fun aLongPressOnTheWalletHeaderOpensTheWalletOptions() {
        val route = Route(accounts = 3)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState())
            }
        }

        compose.onNodeWithText("SEED WALLET").performTouchInput { longClick() }
        compose.onNodeWithText("Edit wallet profile").assertIsDisplayed()
    }

    @Test
    fun showingTheSecretAsksForAWarningBeforeItOpens() {
        val route = Route(accounts = 3)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState())
            }
        }

        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Show secret phrase").performClick()
        compose.onNodeWithText("This reveals your secret recovery phrase", substring = true).assertIsDisplayed()
        assertFalse(route.revealed)

        // Cancel goes back to the options without opening the secret.
        compose.onNodeWithText("CANCEL").performClick()
        compose.onNodeWithText("Show secret phrase").assertIsDisplayed()
        assertFalse(route.revealed)

        compose.onNodeWithText("Show secret phrase").performClick()
        compose.onNodeWithText("REVEAL").performClick()
        assertTrue(route.revealed)
    }

    @Test
    fun aSingleKeyWalletHasNoAddOrGenerateAction() {
        val route = Route(accounts = 1)
        compose.setContent {
            LenslyTheme {
                Detail(route, rememberLazyListState(), kind = WalletKind.SINGLE_KEY)
            }
        }

        compose.onNodeWithText("ADD ACCOUNT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Wallet options").performClick()
        compose.onNodeWithText("Generate accounts").assertDoesNotExist()
        compose.onNodeWithText("Select accounts").assertDoesNotExist()
        compose.onNodeWithText("Show private key").assertIsDisplayed()
    }

}

/** Account 0 has a name, so a test can find its row. The other accounts show their Auto-Names. */
private val profiles = mapOf(0 to AccountProfile(name = "Main", colour = ProfileColour.BLUE, label = "Everyday"))

/** A distinct address for account [index], in the shape the app shows: 0x and forty hex digits. */
private fun addressAt(index: Int): String = "0x%040X".format(0x1000 + index)
