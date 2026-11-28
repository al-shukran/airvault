package app.lensly.ui.settings

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.WhitelistEntry

/**
 * The protocol an approved call belongs to: the text of its meaning before the first colon, such as "Aave V3" in
 * "Aave V3: deposit an asset". A meaning with no colon names no protocol, and the list then shows "Approved Call".
 */
internal fun protocolOf(entry: WhitelistEntry): String = protocolOfMeaning(entry.meaning)

/** The protocol an approved [meaning] names, or empty when the meaning has no colon. */
internal fun protocolOfMeaning(meaning: String): String = meaning.substringBefore(':', "").trim()

/**
 * The approved calls that [query] finds. Every word of the query must appear, ignoring case, in the entry's protocol, function
 * signature, selector, meaning, contract address, chain ID or chain name. A blank query finds every entry, in the order given.
 */
internal fun filterApprovals(entries: List<WhitelistEntry>, networks: List<NetworkEntry>, query: String): List<WhitelistEntry> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return entries
    val chainNames = networks.associate { it.chainId to it.name }
    return entries.filter { entry ->
        val text = listOf(
            protocolOf(entry),
            entry.signature,
            entry.selector,
            entry.meaning,
            entry.contract,
            entry.chainId.toString(),
            chainNames[entry.chainId].orEmpty(),
        ).joinToString(" ").lowercase()
        words.all { it in text }
    }
}
