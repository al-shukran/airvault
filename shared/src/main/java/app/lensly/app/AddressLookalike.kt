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
    val x = lookalikeBody(a, chain)
    val y = lookalikeBody(b, chain)
    if (x.length < 2 * LOOKALIKE_ENDS || y.length < 2 * LOOKALIKE_ENDS) return false
    // The ends are compared first: the key of a bech32 address parses it, so it is taken only for the few that share their ends.
    return x.take(LOOKALIKE_ENDS) == y.take(LOOKALIKE_ENDS) && x.takeLast(LOOKALIKE_ENDS) == y.takeLast(LOOKALIKE_ENDS) &&
        addressKeyOf(a) != addressKeyOf(b)
}

/**
 * The saved entry of [book] that [address] on [chain] looks like, or an account of [internal] as an entry named as the account, or null. An address that is a saved entry is not a lookalike of
 * another, so it returns null for it. The entry must be on the same chain family as [chain], and one on [chain] itself wins over another of the family. A review shows the entry beside the address
 * and never gives the lookalike the entry's name.
 */
fun lookalikeEntryFor(
    address: String,
    chain: ChainId,
    book: List<AddressBookEntry>,
    internal: Map<String, InternalAccount> = emptyMap(),
): AddressBookEntry? {
    if (addressBookEntryFor(address, chain, book) != null) return null
    // The cheap end test runs before the chain, which parses and checksums the entry's address.
    val similar = book.filter { looksLikeAddress(address, it.address, chain) && it.chain?.namespace == chain.namespace }
    similar.firstOrNull { it.chain == chain }?.let { return it }
    similar.firstOrNull()?.let { return it }
    // An account of this device is another target: a lookalike of it is what an address poisoning sends. It shows as an entry under its name.
    return internal.entries.firstOrNull { (key, _) -> looksLikeAddress(address, key, chain) && chainOfAddress(key)?.namespace == chain.namespace }
        ?.let { (key, account) -> AddressBookEntry(id = "", address = key, name = account.displayName ?: account.autoName, label = null) }
}

/** The headline of the amber warning of an address that looks like the saved entry [saved] but differs. Both apps show it. */
fun lookalikeWarningText(saved: AddressBookEntry): String = "Looks like ${isolated(saved.plainName())} but differs"
