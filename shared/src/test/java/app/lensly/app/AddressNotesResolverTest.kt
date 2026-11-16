package app.lensly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressNotesResolverTest {

    private val ownAddress = "0x6982508145454Ce325dDbE47a25d4ec3d2311933"
    private val otherAddress = "0x" + "b".repeat(40)
    private val bitcoinAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

    private val ownAccount = InternalAccount(displayName = "Savings", autoName = "Address 1", label = "Cold")
    private val internal = mapOf(addressKeyOf(ownAddress) to ownAccount)
    private val alice = AddressBookEntry(id = "1", address = otherAddress, name = "Alice", label = "Exchange")
    private val bob = AddressBookEntry(id = "2", address = bitcoinAddress, name = "Bob", label = null)
    private val book = listOf(alice, bob)

    @Test
    fun anAccountOfThisDeviceShowsItsInternalNames() {
        assertEquals(AddressNotes(internal = ownAccount, entry = null), addressNotesOf(ownAddress, internal, book))
    }

    @Test
    fun anEntryShowsItsNameAndLabel() {
        assertEquals(AddressNotes(internal = null, entry = alice), addressNotesOf(otherAddress, internal, book))
    }

    @Test
    fun anAddressThatIsAnAccountAndAnEntryShowsBoth() {
        val mine = AddressBookEntry(id = "3", address = ownAddress.lowercase(), name = "Mine", label = null)

        assertEquals(AddressNotes(internal = ownAccount, entry = mine), addressNotesOf(ownAddress, internal, listOf(mine)))
    }

    @Test
    fun anAddressNeitherSourceKnowsHasNoNotes() {
        assertNull(addressNotesOf("0x" + "c".repeat(40), internal, book))
    }

    @Test
    fun anEthereumAddressMatchesInAnyLetterCase() {
        assertEquals(alice, addressNotesOf(otherAddress.uppercase().replace("0X", "0x"), internal, book)?.entry)
        assertEquals(ownAccount, addressNotesOf(ownAddress.lowercase(), internal, emptyList())?.internal)
    }

    @Test
    fun aBitcoinAddressMatchesExactly() {
        assertEquals(bob, addressNotesOf(bitcoinAddress, emptyMap(), book)?.entry)
        assertNull(addressNotesOf(bitcoinAddress.uppercase(), emptyMap(), book))
    }

    @Test
    fun theNotesOfSeveralAddressesAreKeyedByTheirMatchKey() {
        val notes = addressNotesOf(listOf(ownAddress, otherAddress, otherAddress.uppercase().replace("0X", "0x")), internal, book)

        assertEquals(2, notes.size)
        assertEquals(alice, notes.notesOf(otherAddress.lowercase())?.entry)
        assertEquals(ownAccount, notes.notesOf(ownAddress)?.internal)
        assertNull(notes.notesOf(bitcoinAddress))
    }
}
