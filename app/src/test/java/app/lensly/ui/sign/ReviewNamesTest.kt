package app.lensly.ui.sign

import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.SigningAccount
import app.lensly.app.addressKeyOf
import app.lensly.app.headline
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * The names a Lensly review shows come from [app.lensly.app.plainNameOf], not from the Display Name validator of this module, which throws
 * on a long name. A name that no screen of Lensly could save (an Address Book entry from a pairing, an account name from an old store) must
 * still show, as plain text.
 */
class ReviewNamesTest {
    private val address = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val other = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"
    private val longName = "Savings " + "x".repeat(60)
    private val bidiName = "‮Alice​ Smith‬"
    private val signer = SigningAccount(name = "Main", walletName = "Seed wallet", address = address)

    private fun entry(name: String) = AddressBookEntry(id = "e", address = address, name = name, label = null)

    @Test
    fun aLongNameIsCutNotRefused() {
        val shown = AddressNotes(internal = null, entry = entry(longName)).shownName()
        assertEquals(40, shown?.length)
        assertEquals("…", shown?.takeLast(1))
    }

    @Test
    fun aBidiAndZeroWidthNameShowsPlain() {
        assertEquals("Alice Smith", AddressNotes(internal = null, entry = entry(bidiName)).shownName())
        assertEquals("Alice Smith", AddressNotes(internal = null, entry = entry(bidiName)).headline())
    }

    @Test
    fun anAccountNameIsPlainInTheHeadlineAndTheSentence() {
        val notes = AddressNotes(InternalAccount(displayName = bidiName, autoName = "Key 0", label = "‮Cold"), entry = null)
        assertEquals("Alice Smith", notes.shownName())
        assertEquals("Your account · Alice Smith · Cold", notes.headline())
    }

    @Test
    fun aNameWithNothingVisibleShowsNoName() {
        val notes = AddressNotes(internal = null, entry = entry("​ㅤ ‮"))
        assertNull(notes.shownName())
        assertNull(notes.headline())
    }

    @Test
    fun theSentenceNamesThePartiesInPlainText() {
        val notes = mapOf(addressKeyOf(other) to AddressNotes(InternalAccount(bidiName, "Key 1", null), entry(longName).copy(address = other)))
        val party = sendPartyOf(other, notes, signer.copy(name = longName))
        assertEquals("Alice Smith (${"Savings " + "x".repeat(31)}…)", party?.text)
        assertEquals(longName.take(39) + "…", sendPartyOf(address, notes, signer.copy(name = longName))?.text)
    }

    @Test
    fun aSignerNameWithNothingVisibleHasNoPartyName() {
        assertNull(sendPartyOf(address, emptyMap(), signer.copy(name = "​")))
    }
}
