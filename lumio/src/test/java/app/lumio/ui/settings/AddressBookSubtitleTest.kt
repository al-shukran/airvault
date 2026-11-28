package app.lumio.ui.settings

import app.lumio.pair.ADDRESS_BOOK_ALICE
import app.lumio.pair.ADDRESS_BOOK_COLD
import org.junit.Test
import kotlin.test.assertEquals

/** The line under an Address Book name writes an EVM address as the ends of its groups of 4, and any other address as its first six and last four. */
class AddressBookSubtitleTest {
    @Test
    fun anEvmAddressIsItsEndsInGroupsOfFourAndABitcoinAddressKeepsItsOwnForm() {
        assertEquals("Ethereum · Savings · 0x5aAe b605 … Ef1B eAed", ADDRESS_BOOK_ALICE.subtitle())
        assertEquals("Bitcoin · bc1qw5…f3t4", ADDRESS_BOOK_COLD.subtitle())
    }
}
