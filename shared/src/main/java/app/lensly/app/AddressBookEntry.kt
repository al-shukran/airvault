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

private val EVM_ADDRESS_SHAPE = Regex("0x[0-9a-fA-F]{40}")
private val SOLANA_ADDRESS_SHAPE = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")
private val ALGORAND_ADDRESS_SHAPE = Regex("[A-Z2-7]{58}")

/**
 * Whether [text] has the shape of an address of a chain Lensly signs for: an EVM address, a Solana address in base58, or an Algorand
 * address in base32. A value of another shape, such as an amount or a hash, is not an address.
 */
fun isAddressValue(text: String): Boolean =
    EVM_ADDRESS_SHAPE.matches(text) || SOLANA_ADDRESS_SHAPE.matches(text) || ALGORAND_ADDRESS_SHAPE.matches(text)

/** The distinct addresses among [values], in their order. Each one is looked up in the Address Book. */
fun addressesIn(values: Collection<String>): List<String> = values.filter(::isAddressValue).distinct()
