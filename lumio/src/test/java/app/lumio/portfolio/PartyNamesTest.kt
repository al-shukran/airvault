package app.lumio.portfolio

import app.lensly.app.AddressBookEntry
import app.lensly.app.addressKeyOf
import app.lumio.pair.PairedWallet
import kotlin.test.assertEquals
import org.junit.Test

/** The names the activity shows for a party follow the Address Book's rule: the wallet paired last names a shared entry. */
class PartyNamesTest {
    private val alice = AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", null)

    private fun wallet(id: String, createdAtMs: Long, bookRank: Int, vararg book: AddressBookEntry) =
        PairedWallet(id = id, name = id, fingerprint = 1L, createdAtMs = createdAtMs, bitcoin = emptyList(), addressBook = book.toList(), bookRank = bookRank)

    @Test
    fun aSharedEntryRenamedByARePairShowsTheNewNameWhicheverWalletWasCreatedFirst() {
        val renamed = alice.copy(name = "Alice B.")
        val repaired = wallet("a", createdAtMs = 100L, bookRank = 1, renamed)
        val stale = wallet("b", createdAtMs = 200L, bookRank = 0, alice)

        assertEquals("Alice B.", PortfolioReads.partyNames(listOf(repaired, stale))[addressKeyOf(alice.address)])
        assertEquals("Alice B.", PortfolioReads.partyNames(listOf(stale, repaired))[addressKeyOf(alice.address)])
    }
}
