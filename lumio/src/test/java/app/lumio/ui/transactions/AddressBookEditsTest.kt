package app.lumio.ui.transactions

import app.lensly.app.AddressBookEntry
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.addressBookEntryFor
import kotlin.test.assertEquals
import org.junit.Test

/** The address-book edits of a transaction's sheet: a name is added or renamed by address, and removed by address. */
class AddressBookEditsTest {
    private val friend = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val other = "0x2b5634C42055806a59e9107ED44D43c426E58258"

    @Test
    fun aNewNameAddsAnEntry() {
        val book = addressBookWith(emptyList(), friend, EVM_FAMILY, "Rent", newId = "n1")

        assertEquals(listOf(AddressBookEntry("n1", friend, "Rent", label = null)), book)
    }

    @Test
    fun aRenameKeepsTheIdAndTheLabelAndTheOrder() {
        val before = listOf(AddressBookEntry("a", friend, "Old", label = "Family"), AddressBookEntry("b", other, "Other", label = null))

        val after = addressBookWith(before, friend, EVM_FAMILY, "New", newId = "x")

        assertEquals(listOf(AddressBookEntry("a", friend, "New", label = "Family"), AddressBookEntry("b", other, "Other", label = null)), after)
    }

    @Test
    fun aBitcoinAddressIsNamedAndFoundOnItsChain() {
        val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

        val book = addressBookWith(emptyList(), btc, BITCOIN_FAMILY, "Rent", newId = "n1")

        assertEquals("Rent", addressBookEntryFor(btc, BITCOIN_FAMILY, book)?.name)
        assertEquals(null, addressBookEntryFor(btc, EVM_FAMILY, book))
    }

    @Test
    fun removeDropsOnlyThatAddress() {
        val before = listOf(AddressBookEntry("a", friend, "Friend", label = null), AddressBookEntry("b", other, "Other", label = null))

        assertEquals(listOf(AddressBookEntry("b", other, "Other", label = null)), addressBookWithout(before, friend, EVM_FAMILY))
    }
}
