package app.lensly.app

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

/**
 * The key an address is matched by. An EVM address matches in any letter case, since its case only carries the checksum. A Bitcoin
 * address matches exactly, as its canonical form is kept.
 */
fun addressKeyOf(address: String): String = if (address.startsWith("0x")) address.lowercase() else address
