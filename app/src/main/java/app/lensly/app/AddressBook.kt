package app.lensly.app

import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The entry that the form's [address], [name] and [label] make, under [id]. [book] holds the entries already saved, so an address is in the
 * book once.
 *
 * @throws IllegalArgumentException with the reason, when the address is not a valid EVM or Bitcoin address, the name is not a Display
 * Name, the label is too long or has a control character, or another entry of [book] has the address.
 */
fun addressBookEntryOf(id: String, address: String, name: String, label: String, book: List<AddressBookEntry>): AddressBookEntry {
    val canonical = requireNotNull(canonicalAddressOf(address)) { "Enter a valid Ethereum or Bitcoin address." }
    require(book.none { it.id != id && addressKeyOf(it.address) == addressKeyOf(canonical) }) {
        "That address is already in the Address Book."
    }
    val shownName = name.trim()
    require(isValidDisplayName(shownName)) { "Enter a name of 1 to $DISPLAY_NAME_MAX_LENGTH characters." }
    val shownLabel = label.trim()
    require(shownLabel.isEmpty() || isValidProfileText(shownLabel)) { "Enter a label of up to $PROFILE_TEXT_MAX_LENGTH characters." }
    return AddressBookEntry(id = id, address = canonical, name = shownName, label = shownLabel.ifEmpty { null })
}

/**
 * The Address Book, stored as the vault record `address-book` through [VaultSession.putVaultRecord]. A vault record belongs to the vault, so
 * it is sealed under the Vault Key, removed when the vault is wiped, and kept apart from a Decoy Vault's. The record holds a version byte
 * (1), the entry count (int), then each entry as four texts: id, address, name and label (empty when there is none). A text is its length
 * (int) and its UTF-8 bytes, as the Review Records write theirs. A later pairing sync can send these same fields.
 *
 * [load] throws [VaultException] when the record cannot be read, so a save never replaces a book it could not read.
 */
class AddressBookStore(private val session: VaultSession) {

    /** The saved entries, in the order they were saved. An empty book has none. @throws VaultException if the book cannot be read. */
    fun load(): List<AddressBookEntry> = session.getVaultRecord(NAME)?.let(::decode) ?: emptyList()

    /** Saves [entry]. A new entry is added, and an entry with the same id is replaced. */
    fun put(entry: AddressBookEntry) {
        val entries = load()
        val next = if (entries.any { it.id == entry.id }) {
            entries.map { if (it.id == entry.id) entry else it }
        } else {
            entries + entry
        }
        session.putVaultRecord(NAME, encode(next))
    }

    /** Removes the entry with id [id]. A book without it is unchanged. */
    fun delete(id: String) {
        session.putVaultRecord(NAME, encode(load().filterNot { it.id == id }))
    }

    private companion object {
        const val NAME = "address-book"
        const val VERSION = 1
        const val MAX_ENTRIES = 10_000
        const val MAX_TEXT_BYTES = 4096

        fun encode(entries: List<AddressBookEntry>): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(entries.size)
                entries.forEach { entry ->
                    out.writeText(entry.id)
                    out.writeText(entry.address)
                    out.writeText(entry.name)
                    out.writeText(entry.label.orEmpty())
                }
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): List<AddressBookEntry> = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readUnsignedByte() == VERSION) { "Unknown Address Book version." }
                val count = input.readInt()
                require(count in 0..MAX_ENTRIES) { "Too many Address Book entries." }
                List(count) {
                    AddressBookEntry(
                        id = input.readText(),
                        address = input.readText(),
                        name = input.readText(),
                        label = input.readText().ifEmpty { null },
                    )
                }
            }
        } catch (e: IOException) {
            throw VaultException("The Address Book is damaged.", e)
        } catch (e: IllegalArgumentException) {
            throw VaultException("The Address Book is damaged.", e)
        }

        fun DataOutputStream.writeText(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        fun DataInputStream.readText(): String {
            val size = readInt()
            require(size in 0..MAX_TEXT_BYTES) { "Address Book text is out of range." }
            return String(ByteArray(size).also { readFully(it) }, Charsets.UTF_8)
        }
    }
}
