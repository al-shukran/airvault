package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AddressNotesTest {
    private val ownAddress = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val otherAddress = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val unknownAddress = "0xde709f2102306220921060314715629080e2fb77"

    private val ownAccount = InternalAccount(displayName = "Savings", autoName = "Key 0", label = "Cold")
    private val entry = AddressBookEntry(id = "entry-1", address = otherAddress, name = "Exchange", label = "Deposits")

    private val internal = mapOf(addressKeyOf(ownAddress) to ownAccount)

    @Test
    fun anInternalAccountOnlyGivesItsNames() {
        assertEquals(AddressNotes(internal = ownAccount, entry = null), addressNotesOf(ownAddress, EVM_FAMILY, internal, emptyList()))
    }

    @Test
    fun anAddressBookEntryOnlyGivesItsNames() {
        assertEquals(AddressNotes(internal = null, entry = entry), addressNotesOf(otherAddress, EVM_FAMILY, internal, listOf(entry)))
    }

    @Test
    fun anAddressThatIsBothGivesBothParts() {
        val both = listOf(entry.copy(address = ownAddress))
        assertEquals(AddressNotes(internal = ownAccount, entry = both.single()), addressNotesOf(ownAddress, EVM_FAMILY, internal, both))
    }

    @Test
    fun anUnknownAddressGivesNothing() {
        assertNull(addressNotesOf(unknownAddress, EVM_FAMILY, internal, listOf(entry)))
    }

    @Test
    fun anEvmAddressMatchesInAnyLetterCase() {
        assertEquals(AddressNotes(internal = null, entry = entry), addressNotesOf(otherAddress.lowercase(), EVM_FAMILY, internal, listOf(entry)))
        assertEquals(ownAccount, addressNotesOf(ownAddress.uppercase().replace("0X", "0x"), EVM_FAMILY, internal, emptyList())?.internal)
    }

    @Test
    fun aBitcoinAddressMatchesExactly() {
        val bitcoin = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        val book = listOf(entry.copy(address = bitcoin))
        assertEquals(AddressNotes(internal = null, entry = book.single()), addressNotesOf(bitcoin, BITCOIN_FAMILY, emptyMap(), book))
        assertNull(addressNotesOf(bitcoin.lowercase(), BITCOIN_FAMILY, emptyMap(), book))
    }

    @Test
    fun notesOfReadsAnAddressInAnyCaseFromAMapOfNotes() {
        val notes = addressNotesOf(listOf(ownAddress, otherAddress), EVM_FAMILY, internal, listOf(entry))
        assertEquals(2, notes.size)
        assertEquals(entry, notes.notesOf(otherAddress.lowercase())?.entry)
        assertNull(notes.notesOf(unknownAddress))
    }
}
