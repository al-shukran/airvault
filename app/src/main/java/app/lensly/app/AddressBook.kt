package app.lensly.app

import app.lensly.app.export.PairingCodec
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The most characters an entry's notes take. */
const val ADDRESS_NOTES_MAX_LENGTH = 200

/**
 * True when [notes] is an entry's notes: up to [ADDRESS_NOTES_MAX_LENGTH] characters, with no control or hidden format character. A bidi
 * override or a zero-width character would change how the review reads them, so they are refused as in a name.
 */
fun isValidAddressNotes(notes: String): Boolean = notes.length <= ADDRESS_NOTES_MAX_LENGTH && PairingCodec.hasNoHiddenCharacter(notes)

/**
 * The entry that the form's [address], [name], [label] and [notes] make, under [id]. [book] holds the entries already saved, so an address
 * is in the book once on its chain. The chain is the one the address is valid on.
 *
 * @throws IllegalArgumentException with the reason, when the address is not a valid Ethereum, Bitcoin, Solana or Algorand address, another
 * entry of [book] has the address on the same chain, the name is not a Display Name, the label is too long or has a control or hidden
 * character, or the notes are too long or have one.
 */
fun addressBookEntryOf(
    id: String,
    address: String,
    name: String,
    label: String,
    book: List<AddressBookEntry>,
    notes: String = "",
): AddressBookEntry {
    val canonical = requireNotNull(canonicalAddressOf(address)) { "Enter a valid Ethereum, Bitcoin, Solana or Algorand address." }
    val chain = chainOfAddress(canonical)
    require(book.none { it.id != id && it.chain == chain && addressKeyOf(it.address) == addressKeyOf(canonical) }) {
        "That address is already in the Address Book."
    }
    val shownName = name.trim()
    require(isValidDisplayName(shownName)) {
        "Enter a name of 1 to $DISPLAY_NAME_MAX_LENGTH characters, with no control, hidden or question mark character."
    }
    val shownLabel = label.trim()
    require(shownLabel.isEmpty() || isValidProfileText(shownLabel)) { "Enter a label of up to $PROFILE_TEXT_MAX_LENGTH characters." }
    val shownNotes = notes.trim()
    require(shownNotes.isEmpty() || isValidAddressNotes(shownNotes)) {
        "Enter notes of up to $ADDRESS_NOTES_MAX_LENGTH characters, with no control or hidden character."
    }
    return AddressBookEntry(
        id = id,
        address = canonical,
        name = shownName,
        label = shownLabel.ifEmpty { null },
        notes = shownNotes.ifEmpty { null },
    )
}

/**
 * The Address Book, stored as the vault record `address-book` through [VaultSession.putVaultRecord]. A vault record belongs to the vault, so
 * it is sealed under the Vault Key, removed when the vault is wiped, and kept apart from a Decoy Vault's. The record holds a version byte,
 * then the entry count (int), then each entry as its texts: id, address, name, label and notes. A text is its length (int)
 * and its UTF-8 bytes, as the Review Records write theirs. An empty label or notes is an empty text.
 * A later pairing sync can send these same fields.
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

        /** The version of the book. Each entry holds its notes. */
        const val VERSION = 2
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
                    out.writeText(entry.notes.orEmpty())
                }
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): List<AddressBookEntry> = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readUnsignedByte()
                require(version == VERSION) { "Unknown Address Book version." }
                val count = input.readInt()
                require(count in 0..MAX_ENTRIES) { "Too many Address Book entries." }
                List(count) {
                    val id = input.readText()
                    val address = input.readText()
                    val name = input.readText()
                    val label = input.readText().ifEmpty { null }
                    val notes = input.readText().ifEmpty { null }
                    // An entry is read through the checks that saving runs. A record that fails them is refused, not shown.
                    require(isValidDisplayName(name)) { "An Address Book name is not a valid name." }
                    require(label == null || isValidProfileText(label)) { "An Address Book label is not valid text." }
                    require(notes == null || isValidAddressNotes(notes)) { "Address Book notes are not valid text." }
                    AddressBookEntry(id = id, address = address, name = name, label = label, notes = notes)
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
