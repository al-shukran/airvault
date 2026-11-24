package app.lensly.app

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddressBookStoreTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultKey = ByteArray(32) { (it * 5 + 3).toByte() }

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, vaultKey.copyOf())

    private val alice = AddressBookEntry(
        id = "entry-alice",
        address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
        name = "Alice",
        label = "Exchange deposit",
    )
    private val bob = AddressBookEntry(
        id = "entry-bob",
        address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
        name = "Bob Å✓",
        label = null,
    )

    @Test
    fun aVaultWithNoAddressBookHasNoEntries() {
        assertEquals(emptyList(), AddressBookStore(open()).load())
    }

    @Test
    fun putAddsANewEntryAndReplacesTheEntryWithTheSameId() {
        val session = open()
        val store = AddressBookStore(session)

        store.put(alice)
        store.put(bob)
        store.put(alice.copy(name = "Alice Two", label = null))

        assertEquals(listOf(alice.copy(name = "Alice Two", label = null), bob), store.load())
    }

    @Test
    fun aGroupIsSavedWithItsEntryAndIsTrimmed() {
        val store = AddressBookStore(open())
        val carol = "0x52908400098527886E0F7030069857D2E4169EE7"
        val grouped = addressBookEntryOf("new", carol, "Carol", "", emptyList(), group = " Friends ")

        store.put(grouped)

        assertEquals("Friends", grouped.group)
        assertEquals(listOf(grouped), store.load())
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("new", carol, "Carol", "", emptyList(), group = "x".repeat(PROFILE_TEXT_MAX_LENGTH + 1))
        }
    }

    @Test
    fun deleteRemovesOnlyTheEntryWithThatId() {
        val store = AddressBookStore(open())
        store.put(alice)
        store.put(bob)

        store.delete(alice.id)
        store.delete("no-such-entry")

        assertEquals(listOf(bob), store.load())
    }

    @Test
    fun theBookSurvivesReopeningTheVault() {
        AddressBookStore(open()).put(alice)
        AddressBookStore(open()).put(bob)

        assertEquals(listOf(alice, bob), AddressBookStore(open()).load())
    }

    @Test
    fun aDamagedBookIsReportedAndNotOverwritten() {
        val session = open()
        session.putVaultRecord("address-book", byteArrayOf(9, 9, 9))
        val store = AddressBookStore(session)

        assertFailsWith<VaultException> { store.load() }
        assertFailsWith<VaultException> { store.put(alice) }
        assertContentEquals(byteArrayOf(9, 9, 9), session.getVaultRecord("address-book"))
    }

    @Test
    fun aBookOfTheVersionBeforeTheGroupReadsWithEmptyGroupsAndIsWrittenWithOne() {
        val carol = AddressBookEntry(
            id = "entry-carol",
            address = "0x52908400098527886E0F7030069857D2E4169EE7",
            name = "Carol",
            label = null,
            notes = "Pays rent",
        )
        val session = open()
        session.putVaultRecord("address-book", versionTwoBook(listOf(alice, carol)))
        val store = AddressBookStore(session)

        assertEquals(listOf(alice, carol), store.load())

        store.put(alice.copy(name = "Alice Two"))

        assertEquals(3, session.getVaultRecord("address-book")!![0].toInt())
        assertEquals(listOf(alice.copy(name = "Alice Two"), carol), store.load())
    }

    /** A book as the version before the group wrote it: the version byte 2, then each entry's id, address, name, label and notes. */
    private fun versionTwoBook(entries: List<AddressBookEntry>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(2)
            out.writeInt(entries.size)
            entries.forEach { entry ->
                listOf(entry.id, entry.address, entry.name, entry.label.orEmpty(), entry.notes.orEmpty()).forEach { text ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
        return buffer.toByteArray()
    }

    @Test
    fun canonicalAddressesValidateEvmAndBitcoin() {
        // EVM: a lower-case address takes its checksum form, a mixed-case one must carry the right checksum.
        assertEquals(alice.address, canonicalAddressOf("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
        assertEquals(alice.address, canonicalAddressOf("  ${alice.address}  "))
        assertEquals(null, canonicalAddressOf("0x5aaeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        assertEquals(null, canonicalAddressOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeA"))

        // Bitcoin: Mainnet and Testnet4, and an upper-case bech32 address comes back in lower case.
        assertEquals(bob.address, canonicalAddressOf("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"))
        assertEquals("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", canonicalAddressOf("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"))
        assertEquals(
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
            canonicalAddressOf("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"),
        )
        assertEquals(null, canonicalAddressOf("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"))
        assertEquals(null, canonicalAddressOf("not an address"))
        assertEquals(null, canonicalAddressOf(""))
    }

    @Test
    fun aFormEntryIsCheckedBeforeItIsSaved() {
        val carol = "0x52908400098527886E0F7030069857D2E4169EE7"
        assertEquals(
            AddressBookEntry(id = "new", address = carol, name = "Carol", label = null),
            addressBookEntryOf("new", carol.lowercase(), "  Carol ", "  ", listOf(alice)),
        )
        // The same address in another letter case is the same entry, so it cannot be added twice, but the entry itself can be saved again.
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("new", alice.address.lowercase(), "Again", "", listOf(alice))
        }
        assertEquals(alice.copy(name = "Alice"), addressBookEntryOf(alice.id, alice.address, "Alice", "Exchange deposit", listOf(alice)))
        assertFailsWith<IllegalArgumentException> { addressBookEntryOf("new", "not an address", "Carol", "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { addressBookEntryOf("new", carol, "   ", "", emptyList()) }
        assertFailsWith<IllegalArgumentException> { addressBookEntryOf("new", carol, "x".repeat(DISPLAY_NAME_MAX_LENGTH + 1), "", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("new", carol, "Carol", "x".repeat(PROFILE_TEXT_MAX_LENGTH + 1), emptyList())
        }
    }
}
