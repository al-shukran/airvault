package app.lensly.app

import app.airvault.core.model.ChainId

/**
 * One Address Book entry: a name, an optional label and optional notes for an address the owner saved. It is for display only. No
 * signing rule or policy reads it. [address] is canonical: an EVM address in its checksum form, a Bitcoin address as bitcoin-kmp writes
 * it, or a Solana or Algorand address as written. [id] is random and never changes, so an entry keeps its identity when it is edited or
 * synced.
 */
data class AddressBookEntry(
    val id: String,
    val address: String,
    val name: String,
    /** The label, or null when the owner gave none. */
    val label: String?,
    /** The notes, or null when the owner wrote none. */
    val notes: String? = null,
    /** The group the owner gave the entry, or empty when none. A pairing carries it, and Lumio shows it on the transaction page. */
    val group: String = "",
) {
    /** The chain [address] is valid on, or null when it is not a valid address. A lookup matches an entry on the family of its chain. */
    val chain: ChainId?
        get() = chainOfAddress(address)
}

/**
 * The key an address is matched by. An EVM address matches in any letter case, since its case only carries the checksum, and so does a
 * Bitcoin bech32 address ("bc1" or "tb1"), which a QR code writes in capitals. Solana, Algorand and base58 Bitcoin addresses match exactly, as
 * their canonical form is kept. A Solana address can start with "bc1" too, so a bech32 key is taken only when the address is a Bitcoin one.
 */
fun addressKeyOf(address: String): String = when {
    address.startsWith("0x") -> address.lowercase()
    (address.startsWith("bc1", ignoreCase = true) || address.startsWith("tb1", ignoreCase = true)) &&
        chainOfAddress(address)?.namespace == BITCOIN_FAMILY.namespace -> address.lowercase()
    else -> address
}

/**
 * The entry of [book] that is [address] on [chain], or null when the book has none. The address is matched by [addressKeyOf], and the entry
 * must be on the same chain family as [chain], so an address is looked up on the family it was seen on. An EVM address is the same account
 * on every EVM network, so an entry matches on any of them.
 */
fun addressBookEntryFor(address: String, chain: ChainId, book: List<AddressBookEntry>): AddressBookEntry? {
    val key = addressKeyOf(address)
    return book.firstOrNull { addressKeyOf(it.address) == key && it.chain?.namespace == chain.namespace }
}

/**
 * Whether [text] is an address of a chain Lensly signs for, as [chainOfAddress] classifies it. A value of another
 * shape, such as an amount or a hash, is not an address.
 */
fun isAddressValue(text: String): Boolean = chainOfAddress(text.trim()) != null

/** The distinct addresses among [values], in their order. Each one is looked up in the Address Book. */
fun addressesIn(values: Collection<String>): List<String> = values.filter(::isAddressValue).distinct()
