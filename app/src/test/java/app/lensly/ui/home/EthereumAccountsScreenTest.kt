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
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.theme.LumenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Ethereum App of a wallet: its account list. Adding an account keeps the scroll position and appends its row. Each row shows its
 * full address, and a long press or a tap on an account opens its options. The App's options hold generating, selecting, pairing and
 * signing. A Single Key wallet has one account, so it has no add, generate or select action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
class EthereumAccountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** What the route keeps while the App is open: the account count, the addresses derived so far, and what was asked for. */
    private class Route(accounts: Int) {
        var count by mutableIntStateOf(accounts)
        var addresses by mutableStateOf(List(accounts, ::addressAt))
        var exported: List<Int> = emptyList()
        var edited = -1
        var generated = false
        var paired = false
        var scanned = false

        /** Derives the address of the account the list does not have yet, as the route does after the count changes. */
        fun deriveNext() {
            addresses = addresses + addressAt(addresses.size)
        }
    }

    @Composable
    private fun Ethereum(route: Route, listState: LazyListState, kind: WalletKind = WalletKind.MNEMONIC) {
        EthereumAccountsScreen(
            wallet = WalletMeta(
                id = "w1",
                name = "Seed wallet",
                createdAtMs = 0L,
                deviceGenerated = true,
                accountCount = route.count,
                masterFingerprintHex = "1a2b3c4d",
                accountKeys = emptyList(),
                kind = kind,
                autoNameBase = "Seed wallet",
            ),
            addresses = route.addresses,
            accountProfiles = profiles,
            onBack = {},
            onAddAccount = { route.count += 1 },
            onBulkGenerate = { route.generated = true },
            onPair = { route.paired = true },
            onScan = { route.scanned = true },
            onEditAccount = { route.edited = it },
            onExportAccounts = { route.exported = it },
            onRenameAccounts = {},
            onStyleAccounts = {},
            onResetAccountNames = {},
            listState = listState,
        )
    }

    @Test
    fun addingAnAccountKeepsTheScrollPositionAndAppendsTheRow() {
        // Twelve accounts, with the list scrolled into the middle, so the "Add account" row is still on screen.
        val route = Route(accounts = 12)
        lateinit var listState: LazyListState
        compose.setContent {
            LumenTheme {
                listState = rememberLazyListState()
                LaunchedEffect(Unit) { listState.scrollToItem(4, 30) }
                Ethereum(route, listState)
            }
        }
        compose.waitForIdle()
        val before = compose.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        assertTrue("the list starts scrolled, so the test can see it move", before.first > 0)

        compose.onNodeWithText("Add account").performClick()
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
            LumenTheme {
                Ethereum(route, rememberLazyListState())
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
            LumenTheme {
                Ethereum(route, rememberLazyListState())
            }
        }

        compose.onNodeWithText("Main").performTouchInput { longClick() }
        compose.onNodeWithText("Export address").assertIsDisplayed()
        compose.onNodeWithText("Edit profile").assertIsDisplayed()

        compose.onNodeWithText("Export address").performClick()
        assertEquals(listOf(0), route.exported)
    }

    @Test
    fun exportingASelectionSendsEachSelectedAccountOnceInListOrder() {
        val route = Route(accounts = 5)
        compose.setContent {
            LumenTheme {
                Ethereum(route, rememberLazyListState())
            }
        }

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Select accounts").performClick()
        compose.onNodeWithText(groupedAddress(addressAt(4))).performClick()
        compose.onNodeWithText(groupedAddress(addressAt(1))).performClick()
        compose.onNodeWithText(groupedAddress(addressAt(3))).performClick()
        compose.onNodeWithText("3 SELECTED").assertIsDisplayed()

        compose.onNodeWithText("Export").performClick()
        assertEquals(listOf(1, 3, 4), route.exported)
    }

    @Test
    fun theAppOptionsOfferGeneratingPairingAndSigning() {
        val route = Route(accounts = 3)
        compose.setContent {
            LumenTheme {
                Ethereum(route, rememberLazyListState())
            }
        }

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Generate accounts").performClick()
        assertTrue(route.generated)

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Pair this wallet").performClick()
        assertTrue(route.paired)

        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Scan to sign").performClick()
        assertTrue(route.scanned)
    }

    @Test
    fun aSingleKeyWalletHasNoAddGenerateOrSelectAction() {
        val route = Route(accounts = 1)
        compose.setContent {
            LumenTheme {
                Ethereum(route, rememberLazyListState(), kind = WalletKind.SINGLE_KEY)
            }
        }

        compose.onNodeWithText("Add account").assertDoesNotExist()
        compose.onNodeWithContentDescription("App options").performClick()
        compose.onNodeWithText("Generate accounts").assertDoesNotExist()
        compose.onNodeWithText("Select accounts").assertDoesNotExist()
        compose.onNodeWithText("Pair this wallet").assertIsDisplayed()
    }
}

/** Account 0 has a name, so a test can find its row. The other accounts show their Auto-Names. */
private val profiles = mapOf(0 to AccountProfile(name = "Main", colour = ProfileColour.BLUE, label = "Everyday"))

/** A distinct address for account [index], in the shape the app shows: 0x and forty hex digits. */
private fun addressAt(index: Int): String = "0x%040X".format(0x1000 + index)
