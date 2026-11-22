package app.lumio.search

/**
 * One thing a search can find: its [title] and [detail] as the row shows them, the [terms] it is matched by, and the [target] the row opens. Every
 * term is matched without case, so "usd" finds USD Coin by its name, and "usdc" by its ticker.
 */
data class SearchEntry<T>(val title: String, val detail: String, val terms: List<String>, val target: T)

/** The entries whose terms contain [query], in their given order. A blank query finds nothing, because the sheet shows its hint until the owner types. */
fun <T> matching(query: String, entries: List<SearchEntry<T>>): List<SearchEntry<T>> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return entries.filter { entry -> entry.terms.any { it.contains(needle, ignoreCase = true) } }
}

/**
 * Whether [query] reads as a whole blockchain address: an EVM address, a Bitcoin address (Segwit or legacy), a Solana address or an Algorand address.
 * Pasting one shows the Send row first, so a pasted address goes straight to the recipient.
 */
fun looksLikeAddress(query: String): Boolean {
    val text = query.trim()
    return ADDRESS_SHAPES.any { it.matches(text) }
}

private val ADDRESS_SHAPES = listOf(
    Regex("0x[0-9a-fA-F]{40}"),
    Regex("bc1[ac-hj-np-z02-9]{11,71}"),
    Regex("[13][a-km-zA-HJ-NP-Z1-9]{25,34}"),
    Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"),
    Regex("[A-Z2-7]{58}"),
)
