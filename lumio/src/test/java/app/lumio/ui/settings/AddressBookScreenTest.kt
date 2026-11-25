package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.pair.ADDRESS_BOOK_ALICE
import app.lumio.pair.ADDRESS_BOOK_COLD
import app.lumio.pair.ADDRESS_BOOK_FAUCET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Address Book lists its entries under a header for each Group, in alphabetical order, and the entries with no group under no header. A search
 * narrows them, and a row opens the address's sheet.
 */
@RunWith(RobolectricTestRunner::class)
class AddressBookScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun entriesSitUnderTheHeaderOfTheirGroupAndTheUngroupedLast() {
        val bob = ADDRESS_BOOK_ALICE.copy(id = "5f0c2a1e-0004", name = "Bob")
        val entries = listOf(ADDRESS_BOOK_COLD, ADDRESS_BOOK_ALICE, ADDRESS_BOOK_FAUCET.copy(group = "Exchanges"), bob)
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = entries, offline = null, onEdit = {}, onBack = {}, onLock = {})
            }
        }

        // Exchanges sorts before Friends, and the two Friends entries keep their order. Cold storage has no group, so it is last under no header.
        val inOrder = listOf("EXCHANGES · 1", "Testnet faucet", "FRIENDS · 2", "Alice", "Bob", "Cold storage")
        val tops = inOrder.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("Expected $inOrder top to bottom, found $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun aTestnet4EntryNamesItsNetworkSoItIsNotTakenForBitcoin() {
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = listOf(ADDRESS_BOOK_FAUCET), offline = null, onEdit = {}, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithText("Bitcoin Testnet4 · Testnet4", substring = true).assertIsDisplayed()
    }

    @Test
    fun aBlankGroupIsNoGroupSoItGetsNoNamelessHeader() {
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = listOf(ADDRESS_BOOK_COLD.copy(group = " ")), offline = null, onEdit = {}, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithText("Cold storage").assertIsDisplayed()
        compose.onAllNodes(hasText("· 1", substring = true)).assertCountEquals(0)
    }

    @Test
    fun everyRowNamesTheChainOfItsAddressBesideTheLabel() {
        val solana = ADDRESS_BOOK_COLD.copy(id = "5f0c2a1e-0005", name = "Sol", address = "11111111111111111111111111111111", label = null)
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD, solana), offline = null, onEdit = {}, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithText("Ethereum", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Bitcoin", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Solana", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSearchKeepsTheEntriesWhoseNameLabelOrAddressHoldsTheText() {
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD, ADDRESS_BOOK_FAUCET), offline = null, onEdit = {}, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithTag(ADDRESS_SEARCH_TAG).performTextInput("cold")
        compose.onNodeWithText("Cold storage").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertDoesNotExist()
        compose.onNodeWithTag(ADDRESS_SEARCH_TAG).performTextInput(" nobody")
        compose.onNodeWithText("No entry matches your search.").assertIsDisplayed()
    }

    @Test
    fun aRowOpensTheSheetOfItsAddressAndEditHandsOverToLensly() {
        var edited: String? = null
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(entries = listOf(ADDRESS_BOOK_COLD), offline = null, onEdit = { edited = it }, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithText("Cold storage").performClick()
        compose.onNodeWithText("Edit on Lensly").performClick()
        assertEquals(ADDRESS_BOOK_COLD.address, edited)
    }
}
