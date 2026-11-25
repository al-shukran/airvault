package app.lumio.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.AddressBookEntry
import app.lensly.ui.theme.ClearAirTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The address-book sheet: an unknown address offers to be added on Lensly, and a known one shows its entry and offers to be edited there. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressInfoSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val address = "0x" + "5c".repeat(20)

    @Test
    fun anUnknownAddressSaysItSeenItAndOffersToAddIt() {
        val actions = mutableListOf<String>()
        compose.setContent {
            ClearAirTheme {
                AddressInfoSheet(
                    address = address,
                    entry = null,
                    walletName = "Air 1",
                    seenIn = 2,
                    onEdit = { actions += "edit" },
                    onAdd = { actions += "add" },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Not in Address Book").assertIsDisplayed()
        compose.onNodeWithText("Seen in 2 transactions with Air 1").assertIsDisplayed()
        compose.onNodeWithText("Copy Address").assertIsDisplayed()
        compose.onNodeWithText("Add on Lensly").performClick()

        assertEquals(listOf("add"), actions)
    }

    @Test
    fun aGroupedAddressSaysItsGroup() {
        val entry = AddressBookEntry(id = "entry-1", address = address, name = "Lena Park", label = null, group = "Friends")
        compose.setContent {
            ClearAirTheme {
                AddressInfoSheet(
                    address = address,
                    entry = entry,
                    walletName = "Air 1",
                    seenIn = 2,
                    onEdit = {},
                    onAdd = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Address book · Group Friends").assertIsDisplayed()
        compose.onNodeWithText("Friends").assertIsDisplayed()
    }

    @Test
    fun aKnownAddressShowsItsEntryAndOffersToEditIt() {
        val actions = mutableListOf<String>()
        val entry = AddressBookEntry(id = "entry-1", address = address, name = "Lena Park", label = "Monad test buddy", notes = "Splits test MON")
        compose.setContent {
            ClearAirTheme {
                AddressInfoSheet(
                    address = address,
                    entry = entry,
                    walletName = "Air 1",
                    seenIn = 2,
                    onEdit = { actions += "edit" },
                    onAdd = { actions += "add" },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Lena Park").assertIsDisplayed()
        compose.onNodeWithText("Address book · from Lensly").assertIsDisplayed()
        compose.onNodeWithText("Monad test buddy").assertIsDisplayed()
        compose.onNodeWithText("Splits test MON").assertIsDisplayed()
        compose.onNodeWithText("Edit on Lensly").performClick()

        assertEquals(listOf("edit"), actions)
    }

    @Test
    fun aNameWithHiddenCharactersIsShownAsPlainTextAboveTheFullAddress() {
        val entry = AddressBookEntry(id = "entry-1", address = address, name = "Lena\u202E Park\u200B", label = null)
        compose.setContent {
            ClearAirTheme {
                AddressInfoSheet(address = address, entry = entry, walletName = "Air 1", seenIn = null, onEdit = {}, onAdd = {}, onClose = {})
            }
        }

        compose.onNodeWithText("Lena Park").assertIsDisplayed()
        compose.onNodeWithText("0x5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c").assertIsDisplayed()
    }
}
