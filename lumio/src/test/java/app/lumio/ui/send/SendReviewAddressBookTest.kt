package app.lumio.ui.send

import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.ui.components.groupAddress
import app.lumio.isFirstTime
import app.lumio.lookalikeWarnings
import app.lumio.nameOrAddress
import app.lumio.nameWithAddress
import app.lumio.nameWithStart
import app.lumio.notesDetail
import app.lumio.notesName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The names the send's pages give the recipient: a name in the Address Book wins, an account of this device is "Your account", and the detail says
 * which of the two it is. An address that is both shows the Address Book name, and the account is named by its detail.
 */
class SendReviewAddressBookTest {

    private val alice = AddressBookEntry(id = "1", address = "0x" + "a1".repeat(20), name = "Alice", label = null)
    private val savings = InternalAccount(displayName = "Savings", autoName = "Vault 0", label = "Cold")
    private val vault1 = InternalAccount(displayName = null, autoName = "Vault 1", label = null)

    @Test
    fun aSavedNameIsTheTitleOfAnAddressInTheAddressBook() {
        val notes = AddressNotes(internal = null, entry = alice)

        assertEquals("Alice", notesName(notes))
        assertEquals("Address Book\nSaved for all EVM networks", notesDetail(notes))
    }

    @Test
    fun aLookalikeOnlyGivesNoNameAndAWarningNamingTheSavedEntry() {
        val notes = AddressNotes(internal = null, entry = null, lookalike = alice)

        assertEquals("", notesName(notes))
        assertEquals(true, isFirstTime(notes))
        assertEquals("0x1234 5678", nameOrAddress(notes, "0x12345678"))
        assertEquals("Looks like Alice but differs. Alice is saved as ${groupAddress(alice.address)}.", lookalikeWarnings(notes).single())
        assertEquals("Alice (0x1234 5678)", nameWithAddress(AddressNotes(null, alice), "0x12345678"))
        assertEquals("Alice (0x1234 5678 …)", nameWithStart(AddressNotes(null, alice), "0x1234567890abcdef"))
        assertEquals("0x1234 5678 90ab cdef", nameWithStart(notes, "0x1234567890abcdef"))
    }

    @Test
    fun anAccountOfThisDeviceIsYourAccountWithItsLabel() {
        val notes = AddressNotes(internal = savings, entry = null)

        assertEquals("Savings", notesName(notes))
        assertEquals("Your account", notesDetail(notes))
    }

    @Test
    fun anAddressThatIsAnAccountAndAnEntryShowsTheEntryName() {
        val notes = AddressNotes(internal = vault1, entry = alice)

        assertEquals("Alice", notesName(notes))
        assertEquals("Your account", notesDetail(notes))
    }
}
