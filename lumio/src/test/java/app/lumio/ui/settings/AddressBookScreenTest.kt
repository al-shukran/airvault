package app.lumio.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.pair.ADDRESS_BOOK_ALICE
import app.lumio.pair.ADDRESS_BOOK_COLD
import app.lumio.pair.ADDRESS_BOOK_FAUCET
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Address Book lists its entries under a header for each Group, in alphabetical order, and the entries with no group under no header. */
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
                AddressBookScreen(walletName = "Air 1", entries = entries, offline = null, onBack = {}, onLock = {})
            }
        }

        // Exchanges sorts before Friends, and the two Friends entries keep their order. Cold storage has no group, so it is last under no header.
        val inOrder = listOf("EXCHANGES · 1", "Testnet faucet", "FRIENDS · 2", "Alice", "Bob", "Cold storage")
        val tops = inOrder.map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("Expected $inOrder top to bottom, found $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun aBlankGroupIsNoGroupSoItGetsNoNamelessHeader() {
        compose.setContent {
            ClearAirTheme {
                AddressBookScreen(walletName = "Air 1", entries = listOf(ADDRESS_BOOK_COLD.copy(group = " ")), offline = null, onBack = {}, onLock = {})
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
                AddressBookScreen(walletName = "Air 1", entries = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD, solana), offline = null, onBack = {}, onLock = {})
            }
        }

        compose.onNodeWithText("Ethereum", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Bitcoin").assertIsDisplayed()
        compose.onNodeWithText("Solana").performScrollTo().assertIsDisplayed()
    }
}
