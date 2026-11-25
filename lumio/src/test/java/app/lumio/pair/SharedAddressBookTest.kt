package app.lumio.pair

import app.lensly.app.AddressBookEntry
import kotlin.test.assertEquals
import org.junit.Test

/** The Address Book of the Settings page, which its row counts too: the books of every paired wallet as one, with a shared entry once. */
class SharedAddressBookTest {
    private val shared = AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", null)
    private val other = AddressBookEntry("5f0c2a1e-0002", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null)

    private fun wallet(id: String, bookRank: Int, vararg book: AddressBookEntry) =
        PairedWallet(id = id, name = id, fingerprint = 1L, createdAtMs = 1L, bitcoin = emptyList(), addressBook = book.toList(), bookRank = bookRank)

    @Test
    fun anEntryTwoWalletsShareIsOneEntry() {
        val book = sharedAddressBook(listOf(wallet("a", 1, shared, other), wallet("b", 2, shared)))

        assertEquals(listOf(shared, other), book)
    }

    @Test
    fun aSharedEntryTakesTheCopyOfTheWalletWhoseBookWasPairedLastInTheOrderItWasFirstSeen() {
        val renamed = shared.copy(name = "Alice B.")
        val older = wallet("a", 1, shared, other)
        val newer = wallet("b", 2, renamed)

        assertEquals(listOf(renamed, other), sharedAddressBook(listOf(older, newer)))
        assertEquals(listOf(renamed, other), sharedAddressBook(listOf(newer, older)))
    }

    @Test
    fun aWalletWithNoBookRanksBelowOneWithABookAndAddsNothing() {
        val renamed = shared.copy(name = "Alice B.")

        assertEquals(listOf(renamed), sharedAddressBook(listOf(wallet("a", -1), wallet("b", 0, renamed))))
        assertEquals(listOf(renamed), sharedAddressBook(listOf(wallet("b", 0, renamed), wallet("a", -1))))
    }
}
