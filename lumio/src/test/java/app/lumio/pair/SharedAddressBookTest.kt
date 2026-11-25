package app.lumio.pair

import app.lensly.app.AddressBookEntry
import kotlin.test.assertEquals
import org.junit.Test

/** The Address Book of the Settings page, which its row counts too: the books of every paired wallet as one, with a shared entry once. */
class SharedAddressBookTest {
    private val shared = AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", null)
    private val other = AddressBookEntry("5f0c2a1e-0002", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null)

    private fun wallet(id: String, createdAtMs: Long, vararg book: AddressBookEntry) =
        PairedWallet(id = id, name = id, fingerprint = 1L, createdAtMs = createdAtMs, bitcoin = emptyList(), addressBook = book.toList())

    @Test
    fun anEntryTwoWalletsShareIsOneEntry() {
        val book = sharedAddressBook(listOf(wallet("a", 1L, shared, other), wallet("b", 2L, shared)))

        assertEquals(listOf(shared, other), book)
    }

    @Test
    fun aSharedEntryTakesTheCopyOfTheMostRecentlyPairedWalletInTheOrderItWasFirstSeen() {
        val renamed = shared.copy(name = "Alice B.")
        val older = wallet("a", 1L, shared, other)
        val newer = wallet("b", 2L, renamed)

        assertEquals(listOf(renamed, other), sharedAddressBook(listOf(older, newer)))
        assertEquals(listOf(renamed, other), sharedAddressBook(listOf(newer, older)))
    }
}
