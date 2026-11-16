package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingCodec
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The Address Book that each paired wallet holds, as Lensly sent it with the pairing. The books of all paired wallets are one vault record,
 * [NAME], sealed under the Vault Key like the other vault records, so a saved name or address is not readable from the vault files. The
 * record holds a version byte (1), the number of wallets, and for each wallet its id, its entry count and its entries. An entry is four
 * texts: id, address, name and label (empty when there is none). Lumio never edits a book. A pairing replaces its wallet's book with the one
 * it carries, and an empty book is no book.
 */
internal object PairAddressBooks {
    /** The vault record that holds the books. It is listed among the known vault record names, so a Decoy Vault removal keeps it. */
    const val NAME = "lumio-address-books"

    private const val VERSION = 1
    private const val MAX_WALLETS = 1_024
    private const val MAX_TEXT_BYTES = PairingCodec.MAX_ENTRY_NAME_BYTES

    /** The books by wallet id. A wallet without a book is not in the map. @throws VaultException when the record cannot be read. */
    fun load(session: VaultSession): Map<String, List<AddressBookEntry>> = session.getVaultRecord(NAME)?.let(::decode) ?: emptyMap()

    /**
     * Replaces the book of wallet [walletId] with [entries]. The earlier book is not merged in, and an empty list removes it. The books of
     * the other wallets are kept.
     *
     * @throws VaultException when the record cannot be read, so a save never replaces a book it could not read.
     */
    fun replace(session: VaultSession, walletId: String, entries: List<AddressBookEntry>) {
        val books = LinkedHashMap(load(session))
        if (entries.isEmpty()) books.remove(walletId) else books[walletId] = entries
        session.putVaultRecord(NAME, encode(books))
    }

    private fun encode(books: Map<String, List<AddressBookEntry>>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(VERSION)
            out.writeInt(books.size)
            for ((walletId, entries) in books) {
                out.writeText(walletId)
                out.writeInt(entries.size)
                for (entry in entries) {
                    out.writeText(entry.id)
                    out.writeText(entry.address)
                    out.writeText(entry.name)
                    out.writeText(entry.label.orEmpty())
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun decode(bytes: ByteArray): Map<String, List<AddressBookEntry>> = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == VERSION) { "Unknown address book version." }
            val wallets = input.readInt()
            require(wallets in 0..MAX_WALLETS) { "Too many wallets." }
            val books = LinkedHashMap<String, List<AddressBookEntry>>()
            repeat(wallets) {
                val walletId = input.readText()
                val count = input.readInt()
                require(count in 0..PairingCodec.MAX_ADDRESS_BOOK) { "Too many entries." }
                books[walletId] = List(count) {
                    AddressBookEntry(
                        id = input.readText(),
                        address = input.readText(),
                        name = input.readText(),
                        label = input.readText().ifEmpty { null },
                    )
                }
            }
            books
        }
    } catch (e: IOException) {
        throw VaultException("The address books are damaged.", e)
    } catch (e: IllegalArgumentException) {
        throw VaultException("The address books are damaged.", e)
    }

    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES) { "Address book text is out of range." }
        return String(ByteArray(size).also { readFully(it) }, Charsets.UTF_8)
    }
}
