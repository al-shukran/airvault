package app.lensly.app

import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Address Book in the pairing of a wallet: it goes with the code in the order the entries were added, at most the cap of them, and a
 * book that cannot be read fails the pairing instead of sending none.
 */
class WalletStorePairingBookTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(): String = store.create(
        name = "Test",
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    private fun entry(i: Int, label: String? = null) = AddressBookEntry(
        id = "id-$i",
        address = "0x" + "%040x".format(i + 1),
        name = "Contact $i",
        label = label,
    )

    private fun pairingOf(parts: List<String>): PairingPayload {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return PairingCodec.fromUr(requireNotNull(scanner.result()), null)
    }

    @Test
    fun thePairingCarriesTheAddressBookInTheOrderItWasAdded() {
        val id = newWallet()
        val book = listOf(entry(0, label = "Exchange"), entry(1))
        book.forEach { AddressBookStore(session).put(it) }

        assertEquals(book, pairingOf(store.pairingParts(id, null)).addressBook)
    }

    @Test
    fun aWalletWithoutEntriesPairsWithNone() {
        val id = newWallet()

        assertTrue(pairingOf(store.pairingParts(id, null)).addressBook.isEmpty())
    }

    @Test
    fun aBookOfMoreThanTheCapSendsTheFirstEntriesOnly() {
        val id = newWallet()
        val saved = List(PairingCodec.MAX_ADDRESS_BOOK + 2) { i -> entry(i) }
        val book = AddressBookStore(session)
        saved.forEach { book.put(it) }

        assertEquals(saved.take(PairingCodec.MAX_ADDRESS_BOOK), pairingOf(store.pairingParts(id, null)).addressBook)
    }

    @Test
    fun aBookThatCannotBeReadFailsThePairingRatherThanSendingNone() {
        val id = newWallet()
        session.putVaultRecord("address-book", byteArrayOf(9))

        assertThrows(VaultException::class.java) { store.pairingParts(id, null) }
    }
}
