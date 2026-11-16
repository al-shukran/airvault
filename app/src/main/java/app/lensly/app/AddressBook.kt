package app.lensly.app

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import fr.acinq.bitcoin.Bitcoin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.web3j.crypto.Keys

/**
 * One Address Book entry: a name and an optional label for an address the owner saved. It is for display only. No signing rule or
 * policy reads it. [address] is canonical: an EVM address in its checksum form, or a Bitcoin address as bitcoin-kmp writes it. [id]
 * is random and never changes, so an entry keeps its identity when it is edited or synced.
 */
data class AddressBookEntry(
    val id: String,
    val address: String,
    val name: String,
    /** The label, or null when the owner gave none. */
    val label: String?,
)

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/**
 * The EVM address [text] in its checksum form, or null when it is not one. An address in one letter case is taken as it is. A mixed-case
 * address must carry the right checksum.
 */
fun evmAddressOf(text: String): String? {
    if (!EVM_ADDRESS.matches(text)) return null
    val checksum = Keys.toChecksumAddress(text)
    val hex = text.substring(2)
    val mixed = hex != hex.lowercase() && hex != hex.uppercase()
    return if (mixed && checksum != text) null else checksum
}

/**
 * The Bitcoin address [text] as bitcoin-kmp writes it, on Mainnet or Testnet4, or null when it is not a valid address of either. A bech32
 * address comes back in lower case, so its upper-case form, as a QR code gives it, is the same address.
 */
fun btcAddressOf(text: String): String? {
    if (text.isEmpty()) return null
    // A bech32 address is the same address in either case, and no base58 address starts with BC1 or TB1, so only these are lowered.
    val input = if (text.startsWith("BC1") || text.startsWith("TB1")) text.lowercase() else text
    for (network in BtcNetwork.entries) {
        val chainHash = network.chain.chainHash
        val script = Bitcoin.addressToPublicKeyScript(chainHash, input).right ?: continue
        return Bitcoin.addressFromPublicKeyScript(chainHash, script).right
    }
    return null
}

/** The canonical form of [text], trimmed, or null when it is neither a valid EVM address nor a valid Bitcoin address. */
fun canonicalAddressOf(text: String): String? = text.trim().let { evmAddressOf(it) ?: btcAddressOf(it) }

/**
 * The key an address is matched by. An EVM address matches in any letter case, since its case only carries the checksum. A Bitcoin
 * address matches exactly, as its canonical form is kept.
 */
fun addressKeyOf(address: String): String = if (address.startsWith("0x")) address.lowercase() else address

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
