package app.lensly.app

import app.airvault.core.model.ChainId

/** How many characters at each end of an address a lookalike shares with a saved entry. */
private const val LOOKALIKE_ENDS = 4

/**
 * The part of [address] that a lookalike is judged on: the address without its prefix. An EVM address drops "0x" and is lowercased, since
 * its case only carries the checksum. A Bitcoin address drops "bc1" or "tb1" and is lowercased (bech32, whose case carries nothing) or drops its first character (base58). A
 * Bitcoin, Solana or Algorand address is otherwise taken exactly as written.
 */
private fun lookalikeBody(address: String, chain: ChainId): String = when {
    chain.namespace == EVM_FAMILY.namespace -> address.removePrefix("0x").removePrefix("0X").lowercase()
    chain.namespace != BITCOIN_FAMILY.namespace -> address
    address.startsWith("bc1", ignoreCase = true) || address.startsWith("tb1", ignoreCase = true) -> address.drop(3).lowercase()
    else -> address.drop(1)
}

/**
 * Whether [a] and [b], addresses on the family of [chain], are two different addresses that share the first and last 4 characters of their bodies, so that a glance at the
 * ends takes one for the other. Equal addresses (by [addressKeyOf]) are not lookalikes, and a body too short to hold both ends apart is
 * never one.
 */
fun looksLikeAddress(a: String, b: String, chain: ChainId): Boolean {
    if (addressKeyOf(a) == addressKeyOf(b)) return false
    val x = lookalikeBody(a, chain)
    val y = lookalikeBody(b, chain)
    if (x.length < 2 * LOOKALIKE_ENDS || y.length < 2 * LOOKALIKE_ENDS) return false
    return x.take(LOOKALIKE_ENDS) == y.take(LOOKALIKE_ENDS) && x.takeLast(LOOKALIKE_ENDS) == y.takeLast(LOOKALIKE_ENDS)
}

/**
 * The saved entry of [book] that [address] on [chain] looks like, or null. An address that is a saved entry is not a lookalike of
 * another, so it returns null for it. The entry must be on the same chain family as [chain], and one on [chain] itself wins over another of the family. A review shows the entry beside the address
 * and never gives the lookalike the entry's name.
 */
fun lookalikeEntryFor(address: String, chain: ChainId, book: List<AddressBookEntry>): AddressBookEntry? {
    if (addressBookEntryFor(address, chain, book) != null) return null
    // The cheap end test runs before the chain, which parses and checksums the entry's address.
    val similar = book.filter { looksLikeAddress(address, it.address, chain) && it.chain?.namespace == chain.namespace }
    return similar.firstOrNull { it.chain == chain } ?: similar.firstOrNull()
}

/** The headline of the amber warning of an address that looks like the saved entry [saved] but differs. Both apps show it. */
fun lookalikeWarningText(saved: AddressBookEntry): String = "Looks like ${saved.plainName()} but differs"
