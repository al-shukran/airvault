package app.lensly.app

import app.lensly.app.sign.WalletView
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class AddressNotesTest {
    private val ownAddress = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val otherAddress = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val unknownAddress = "0xde709f2102306220921060314715629080e2fb77"

    private val ownAccount = InternalAccount(displayName = "Savings", autoName = "Key 0", label = "Cold")
    private val entry = AddressBookEntry(id = "entry-1", address = otherAddress, name = "Exchange", label = "Deposits")

    private val internal = internalAccountsOf(
        listOf(WalletView.singleKey(id = "w1", address = ownAddress, autoNameBase = "Key", accountName = "Savings", accountLabel = "Cold")),
    )

    @Test
    fun anInternalAccountOnlyGivesItsNames() {
        assertEquals(AddressNotes(internal = ownAccount, entry = null), addressNotesOf(ownAddress, internal, emptyList()))
    }

    @Test
    fun anAddressBookEntryOnlyGivesItsNames() {
        assertEquals(AddressNotes(internal = null, entry = entry), addressNotesOf(otherAddress, internal, listOf(entry)))
    }

    @Test
    fun anAddressThatIsBothGivesBothParts() {
        val both = listOf(entry.copy(address = ownAddress))
        assertEquals(AddressNotes(internal = ownAccount, entry = both.single()), addressNotesOf(ownAddress, internal, both))
    }

    @Test
    fun anUnknownAddressGivesNothing() {
        assertNull(addressNotesOf(unknownAddress, internal, listOf(entry)))
    }

    @Test
    fun anEvmAddressMatchesInAnyLetterCase() {
        assertEquals(AddressNotes(internal = null, entry = entry), addressNotesOf(otherAddress.lowercase(), internal, listOf(entry)))
        assertEquals(ownAccount, addressNotesOf(ownAddress.uppercase().replace("0X", "0x"), internal, emptyList())?.internal)
    }

    @Test
    fun aBitcoinAddressMatchesExactly() {
        val bitcoin = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        val book = listOf(entry.copy(address = bitcoin))
        assertEquals(AddressNotes(internal = null, entry = book.single()), addressNotesOf(bitcoin, emptyMap(), book))
        assertNull(addressNotesOf(bitcoin.lowercase(), emptyMap(), book))
    }

    @Test
    fun anAccountWithNoDisplayNameKeepsItsAutoName() {
        val unnamed = internalAccountsOf(
            listOf(WalletView.singleKey(id = "w2", address = ownAddress, autoNameBase = "Seed")),
        )
        assertEquals(InternalAccount(displayName = null, autoName = "Seed 0", label = null), unnamed[addressKeyOf(ownAddress)])
    }

    @Test
    fun aMultiAccountWalletNamesEachAccountByItsIndex() {
        val wallet = WalletView(
            id = "w3",
            masterFingerprint = 0x1234,
            accountCount = 3,
            autoNameBase = "Vault",
            accountNames = mapOf(1 to "Trading"),
            accountLabels = mapOf(2 to "Cold"),
            addressAt = { index -> "0x" + (index + 1).toString().padStart(40, '0') },
        )
        val accounts = internalAccountsOf(listOf(wallet))
        assertEquals(InternalAccount(null, "Vault 0", null), accounts[addressKeyOf("0x" + "0".repeat(39) + "1")])
        assertEquals(InternalAccount("Trading", "Vault 1", null), accounts[addressKeyOf("0x" + "0".repeat(39) + "2")])
        assertEquals(InternalAccount(null, "Vault 2", "Cold"), accounts[addressKeyOf("0x" + "0".repeat(39) + "3")])
    }

    @Test
    fun notesOfReadsAnAddressInAnyCaseFromAMapOfNotes() {
        val notes = addressNotesOf(listOf(ownAddress, otherAddress), internal, listOf(entry))
        assertEquals(2, notes.size)
        assertEquals(entry, notes.notesOf(otherAddress.lowercase())?.entry)
        assertNull(notes.notesOf(unknownAddress))
    }
}
