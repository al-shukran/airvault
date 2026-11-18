package app.lensly.ui.sign

import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.headline
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The bold line above an address and the name a sentence gives it. An account of this device takes both, over the Address Book name. */
class OwnAccountHeadlineTest {
    private val address = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val entry = AddressBookEntry(id = "entry-1", address = address, name = "Exchange", label = null)

    @Test
    fun anAccountWithALabelHeadlinesItsNameAndLabel() {
        val notes = AddressNotes(InternalAccount(displayName = "Savings", autoName = "Key 0", label = "Cold"), entry = null)

        assertEquals("Your account · Savings · Cold", notes.headline())
        assertEquals("Savings", notes.shownName())
    }

    @Test
    fun anAccountWithoutADisplayNameHeadlinesItsAutoName() {
        val notes = AddressNotes(InternalAccount(displayName = null, autoName = "Seed 0", label = null), entry = null)

        assertEquals("Your account · Seed 0", notes.headline())
        assertEquals("Seed 0", notes.shownName())
    }

    @Test
    fun anAccountWithoutALabelLeavesItOut() {
        val notes = AddressNotes(InternalAccount(displayName = "Savings", autoName = "Key 0", label = null), entry = null)

        assertEquals("Your account · Savings", notes.headline())
    }

    @Test
    fun anAccountTakesTheHeadlineOverTheAddressBookName() {
        val notes = AddressNotes(InternalAccount(displayName = "Savings", autoName = "Key 0", label = "Cold"), entry)

        assertEquals("Your account · Savings · Cold", notes.headline())
        assertEquals("Savings", notes.shownName())
    }

    @Test
    fun anAddressBookEntryWithoutAnAccountHeadlinesItsName() {
        val notes = AddressNotes(internal = null, entry = entry)

        assertEquals("Exchange", notes.headline())
        assertEquals("Exchange", notes.shownName())
    }

    @Test
    fun anAddressWithNoNotesHasNoHeadline() {
        val notes: AddressNotes? = null

        assertNull(notes?.headline())
    }
}
