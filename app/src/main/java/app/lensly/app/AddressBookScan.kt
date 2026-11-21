package app.lensly.app

/** An EIP-681 link to an Ethereum address: the address, then an optional `@` and a decimal chain id, and nothing else. */
private val EIP_681_LINK = Regex("""ethereum:(0x[0-9a-fA-F]{40})(?:@[1-9][0-9]{0,17})?""")

/**
 * The canonical address that a scanned [text] names, or null when it names none that the Address Book takes. The text is a plain address of
 * Ethereum, Bitcoin, Solana or Algorand, or an EIP-681 link `ethereum:0x...` with an optional `@chainId`. The chain id is checked and not
 * kept, since an entry is kept by its address, which is the same on every EVM network.
 */
fun addressOfScan(text: String): String? {
    val linked = EIP_681_LINK.matchEntire(text.trim())
    return canonicalAddressOf(linked?.groupValues?.get(1) ?: text)
}

/** Where a scanned address goes in the Address Book. */
sealed interface ScanTarget {
    /** The book holds the address: its entry opens to edit, so no second entry is made for it. */
    data class Existing(val entry: AddressBookEntry) : ScanTarget

    /** The book does not hold the address: a new entry starts with it. */
    data class New(val address: String) : ScanTarget
}

/** Where the scanned [text] goes in [book], or null when the text names no address the Address Book takes. */
fun scanTargetOf(text: String, book: List<AddressBookEntry>): ScanTarget? {
    val address = addressOfScan(text) ?: return null
    val chain = chainOfAddress(address) ?: return null
    return addressBookEntryFor(address, chain, book)?.let { ScanTarget.Existing(it) } ?: ScanTarget.New(address)
}
